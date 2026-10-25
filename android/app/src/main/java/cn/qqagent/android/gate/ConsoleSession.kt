package cn.qqagent.android.gate

import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** The service owns exactly one original-core child, separate from Linux QQ. */
object ConsoleSession {
    @Volatile private var process: Process? = null
    @Synchronized fun start(builder: ProcessBuilder, home: File, bootstrap: JSONObject): Boolean {
        if (isRunning()) return true
        val child = builder.redirectErrorStream(true).start()
        process = child
        val secrets = listOf(bootstrap.optString("token"), bootstrap.optString("protocolToken"), bootstrap.optJSONObject("api")?.optString("apiKey").orEmpty(),
            bootstrap.optJSONObject("manualApi")?.optString("apiKey").orEmpty(),bootstrap.optJSONObject("credentialBridge")?.optString("token").orEmpty()).filter { it.isNotBlank() }
        Thread {
            try { File(home, "android-console.log").bufferedWriter().use { log ->
                child.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    var safe = line; secrets.forEach { safe = safe.replace(it, "[hidden]") }; log.write(safe); log.newLine(); log.flush()
                } }
            } } catch (_: Exception) { /* Child stream closes on an intentional stop. */ }
        }.apply { name = "qqagent-console-log"; isDaemon = true; start() }
        child.outputStream.write((bootstrap.toString() + "\n").toByteArray()); child.outputStream.flush()
        return true
    }
    fun isRunning() = process?.isAlive == true
    fun reachable() = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 3312), 300) }; true }.getOrDefault(false)
    @Synchronized fun stop() {
        val child = process; process = null
        if (child != null) {
            runCatching { child.outputStream.close() }
            child.destroy()
            if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly()
        }
        SubscriptionCredentialBridge.stop()
    }
}
