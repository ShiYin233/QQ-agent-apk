package cn.qqagent.android.gate

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** One QQ runtime per app. No commands or credentials arrive over intents. */
object RuntimeSession {
    @Volatile private var process: Process? = null
    private var reader: Thread? = null
    fun isRunning(): Boolean = process?.isAlive == true

    fun start(builder: ProcessBuilder, home: File): JSONObject {
        val child = synchronized(this) {
            if (process?.isAlive == true) return status(home).put("alreadyRunning", true)
            LoginQr.files(home).forEach { check(it.delete()) }
            builder.redirectErrorStream(true).start().also { process = it }
        }
        val log = File(home, "qq-session.log")
        if (log.isFile) {
            val archive = File(home, "session-logs")
            check(archive.mkdirs() || archive.isDirectory)
            check(log.renameTo(File(archive, "qq-session-${System.currentTimeMillis()}.log")))
        }
        reader = Thread {
            try { child.inputStream.bufferedReader().use { input ->
                log.bufferedWriter().use { output ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        output.write(buffer, 0, n); output.flush()
                    }
                }
            } } catch (_: IOException) { /* Android closes the pipe when its child is destroyed. */ }
        }.apply { name = "qq-runtime-output"; start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180)
        while (child.isAlive && System.nanoTime() < deadline) {
            if (qrFile(home) != null || portOpen(3000)) break
            Thread.sleep(500)
        }
        return status(home).put("exitCode", if (child.isAlive) JSONObject.NULL else child.exitValue())
    }

    @Synchronized fun stop() {
        process?.let { child ->
            child.destroy()
            if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly()
        }
        reader?.join(1000)
        process = null
    }

    fun status(home: File): JSONObject {
        val webui = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 6099), 300) }; true }.getOrDefault(false)
        return JSONObject().put("running", process?.isAlive == true)
            .put("loginQrReady", qrFile(home) != null).put("webuiReachable", webui)
            .put("gatePassed", false).put("loginVerified", false)
    }

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }; true
    }.getOrDefault(false)

    fun qrFile(home: File): File? {
        return LoginQr.files(home).firstOrNull { it.length() in 1..1024*1024 }
    }
}
