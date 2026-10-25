package cn.qqagent.android.gate

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User model settings, encrypted at rest; API keys are never added to reports. */
class ModelSettingsStore(private val context: Context) {
    private val file = File(context.filesDir, "credentials/model-settings.enc")
    private val alias = "qqagent-model-settings-v1"
    @Synchronized fun read(): JSONObject {
        if (!file.isFile) {
            val previous = runCatching { TestCredentialStore(context).read() }.getOrNull() ?: return JSONObject()
            val modelReport = runCatching { JSONObject(File(context.filesDir, "model-connectivity-report.json").readText()) }.getOrNull()
            val model = modelReport?.optJSONObject("details")?.optString("requestedModel").orEmpty()
            previous.put("model", model.ifEmpty { "deepseek-v4-flash-0731" })
            save(previous); return previous
        }
        val bytes = file.readBytes()
        require(bytes.size > 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val plain = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        return try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
    }
    @Synchronized fun save(value: JSONObject) {
        val address = URI(value.getString("baseUrl"))
        require(address.scheme == "https" && !address.host.isNullOrBlank() && address.userInfo == null) { "模型地址须为 HTTPS" }
        require(value.optString("apiKey").length in 1..512) { "请填写 API Key" }
        val plain = value.toString().toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
            check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
            val staging = File(file.parentFile, "model-settings.enc.partial")
            staging.writeBytes(byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)); check(staging.renameTo(file))
        } finally { plain.fill(0) }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        return store.getKey(alias, null) as SecretKey
    }
}
