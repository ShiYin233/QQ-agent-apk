package cn.qqagent.android.gate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** All requests stay on loopback and authenticate with the private runtime token. */
class ConsoleApi(private val context: Context) {
    private fun connection(path: String): HttpURLConnection {
        require(path.startsWith("/api/") && !path.contains("..") && !path.contains('#'))
        val link = JSONObject(File(context.filesDir, "runtime/state/console-link.json").readText())
        return (URL("http://127.0.0.1:3312$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500; readTimeout = if (path in listOf("/api/android/subscriptions/test","/api/android/subscriptions/refresh-models")) 60000 else 12000; instanceFollowRedirects = false
            setRequestProperty("x-console-token", link.getString("token"))
        }
    }
    fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val connection = connection(path)
        try {
            connection.requestMethod = method
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val result = JSONObject(stream?.bufferedReader()?.use { it.readText() } ?: "{}")
            if (code !in 200..299) throw IllegalStateException(result.optString("error", "请求失败（$code）"))
            return result
        } finally { connection.disconnect() }
    }
    fun setReplies(enabled: Boolean) {
        request("/api/android/replies", "POST", JSONObject().put("enabled", enabled))
        context.getSharedPreferences(RuntimeForegroundService.PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean("repliesEnabled", enabled).apply()
    }
    fun events() = callbackFlow {
        var activeConnection: HttpURLConnection? = null
        val job = launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val current = connection("/api/events"); activeConnection = current
                    current.readTimeout = 25000
                    check(current.responseCode == 200)
                    current.inputStream.bufferedReader().use { reader ->
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            if (line.startsWith("event:")) trySend(line.substringAfter(':').trim())
                        }
                    }
                } catch (_: Exception) { trySend("disconnected") }
                finally { activeConnection?.disconnect(); activeConnection = null }
                kotlinx.coroutines.delay(3000)
            }
        }
        awaitClose { activeConnection?.disconnect(); job.cancel() }
    }
}
