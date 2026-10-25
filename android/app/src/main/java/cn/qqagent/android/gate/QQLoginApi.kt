package cn.qqagent.android.gate

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Fixed NapCat login endpoints only; credentials remain in app-private memory. */
class QQLoginApi(context: Context) {
    private val configFile = File(context.filesDir, "runtime/rootfs/opt/napcat/config/webui.json")
    private var credential = ""
    private var authenticatedAt = 0L
    private fun post(path: String, body: JSONObject = JSONObject(), authenticated: Boolean = true): JSONObject {
        val connection = URL("http://127.0.0.1:6099/api$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2500; connection.readTimeout = 15000; connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (authenticated) connection.setRequestProperty("Authorization", "Bearer $credential")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            check(connection.responseCode in 200..299) { "QQ 登录服务暂时不可用" }
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(result.optInt("code", -1) == 0) { "QQ 登录操作未成功，请稍后重试" }
            return result.optJSONObject("data") ?: JSONObject()
        } finally { connection.disconnect() }
    }
    private fun authenticate() {
        if (credential.isNotBlank() && System.currentTimeMillis() - authenticatedAt < 50 * 60 * 1000) return
        val token = JSONObject(configFile.readText()).getString("token")
        val hash = MessageDigest.getInstance("SHA-256").digest((token + ".napcat").toByteArray()).joinToString("") { "%02x".format(it) }
        credential = post("/auth/login", JSONObject().put("hash", hash), false).getString("Credential")
        authenticatedAt = System.currentTimeMillis()
    }
    @Synchronized fun status(): JSONObject { authenticate(); return post("/QQLogin/CheckLoginStatus") }
    @Synchronized fun refresh(): JSONObject {
        authenticate()
        val current = post("/QQLogin/CheckLoginStatus")
        check(!current.optBoolean("isLogin")) { "QQ 已登录，无需刷新二维码" }
        return post("/QQLogin/RefreshQRcode")
    }
}
