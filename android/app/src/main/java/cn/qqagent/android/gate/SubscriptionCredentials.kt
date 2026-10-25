package cn.qqagent.android.gate

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials never become original-core config, logs, intents or PC artifacts. */
class SubscriptionCredentials(context: Context) {
    private val file = File(context.filesDir, "credentials/subscriptions.enc")
    private val alias = "qqagent-subscriptions-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        return store.getKey(alias, null) as SecretKey
    }
    private fun readAll(): JSONObject {
        if (!file.exists()) return JSONObject()
        val bytes = file.readBytes(); require(bytes.size in 30..262144 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1,13)))
        val plain = cipher.doFinal(bytes.copyOfRange(13,bytes.size))
        return try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
    }
    @Synchronized fun read(provider: String): JSONObject? = readAll().optJSONObject(provider)
    @Synchronized fun write(provider: String, value: JSONObject?) {
        require(provider in listOf("chatgpt-plus", "google-ai-pro"))
        val all = readAll()
        if (value == null) all.remove(provider) else { require(value.toString().length <= 65536); all.put(provider,value) }
        val plain = all.toString().toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key())
            check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
            val staging = File(file.parentFile,"subscriptions.enc.partial")
            staging.writeBytes(byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)); check(staging.renameTo(file))
        } finally { plain.fill(0) }
    }
}

/** A separate authenticated loopback channel exclusively for the app-owned Node child. */
object SubscriptionCredentialBridge {
    private var server: ServerSocket? = null
    private fun pool() = ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(8))
    private var workers = pool()
    private var token = ""
    @Synchronized fun start(context: Context): JSONObject {
        if (server?.isClosed != false) {
            token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val listener = ServerSocket(0,8,InetAddress.getByName("127.0.0.1")); server = listener
            workers = pool()
            val store = SubscriptionCredentials(context.applicationContext)
            Thread {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    runCatching { workers.execute { socket.use { handle(it,store) } } }.onFailure { socket.close() }
                }
            }.apply { name = "qqagent-encrypted-credentials"; isDaemon = true; start() }
        }
        return JSONObject().put("baseUrl","http://127.0.0.1:${server!!.localPort}").put("token",token)
    }
    private fun handle(socket: Socket, store: SubscriptionCredentials) {
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream()
            var headerBytes = 0
            fun line(): String {
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    val b = input.read(); check(b >= 0 && ++headerBytes <= 16384)
                    if (b == 10) break
                    out.write(b)
                }
                return out.toString("US-ASCII").trimEnd('\r')
            }
            val request = line().split(' '); check(request.size == 3)
            val headers = mutableMapOf<String,String>()
            while (true) { val text = line(); if (text.isEmpty()) break; val colon = text.indexOf(':'); check(colon > 0)
                val name = text.substring(0,colon).lowercase(); check(!headers.containsKey(name)); headers[name] = text.substring(colon+1).trim() }
            fun reply(code: Int, value: JSONObject) {
                val bytes = value.toString().toByteArray()
                val status = if (code == 200) "OK" else "Error"
                val header = "HTTP/1.1 $code $status\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n\r\n"
                socket.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
            }
            if (headers["x-credential-token"] != token || headers.containsKey("origin")) { reply(401,JSONObject().put("error","Unauthorized")); return }
            val provider = request[1].removePrefix("/credentials/")
            if (request[1] != "/credentials/$provider" || provider !in listOf("chatgpt-plus","google-ai-pro")) { reply(404,JSONObject()); return }
            check(!headers.containsKey("transfer-encoding"))
            when (request[0]) {
                "GET" -> reply(200,JSONObject().put("credential",store.read(provider) ?: JSONObject.NULL))
                "DELETE" -> { store.write(provider,null); reply(200,JSONObject().put("ok",true)) }
                "PUT" -> {
                    val size = headers["content-length"]?.toIntOrNull() ?: error("Missing length"); check(size in 2..65536)
                    val bytes = ByteArray(size); var offset = 0
                    while (offset < size) { val count = input.read(bytes,offset,size-offset); check(count > 0); offset += count }
                    try { store.write(provider,JSONObject(bytes.toString(Charsets.UTF_8))) } finally { bytes.fill(0) }
                    reply(200,JSONObject().put("ok",true))
                }
                else -> reply(405,JSONObject())
            }
        } catch (_: Exception) { /* Never copy an auth response or credential to a diagnostic log. */ }
    }
    @Synchronized fun stop() { server?.close(); server = null; workers.shutdownNow(); token = "" }
}

object SubscriptionSelection {
    fun api(context: Context, manual: JSONObject): JSONObject {
        val prefs = context.getSharedPreferences(RuntimeForegroundService.PREFERENCES,Context.MODE_PRIVATE)
        val provider = prefs.getString("modelConnection","").orEmpty()
        if (provider !in listOf("chatgpt-plus","google-ai-pro")) return manual
        return JSONObject().put("provider","android-$provider").put("baseUrl","https://subscription.qqagent.invalid/v1/$provider")
            .put("apiKey","android-subscription").put("model",prefs.getString("subscriptionModel-$provider",""))
            .put("visionModel","").put("videoModel","").put("fallbackModels",org.json.JSONArray())
    }
}
