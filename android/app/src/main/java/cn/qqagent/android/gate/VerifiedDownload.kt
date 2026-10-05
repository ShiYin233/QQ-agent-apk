package cn.qqagent.android.gate

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Staging only. Unverified downloads are deleted and never activated or executed. */
class VerifiedDownload(private val connect: (URI) -> HttpURLConnection = {
    it.toURL().openConnection() as HttpURLConnection
}) {
    fun fetch(pin: ComponentPin, directory: File, cancelled: () -> Boolean = { false }, progress: (Long) -> Unit = {}): File {
        pin.validate()
        directory.mkdirs()
        val destination = File(directory, "${pin.id}-${pin.sha256.lowercase()}.artifact")
        if (destination.isFile && destination.length() <= pin.maxDownloadBytes && digest(destination).equals(pin.sha256, true)) {
            return destination
        }
        val staging = File.createTempFile("${pin.id}-", ".partial", directory)
        var connection: HttpURLConnection? = null
        try {
            var uri = URI(pin.url)
            var redirects = 0
            while (true) {
                if (cancelled()) throw InterruptedException("下载已取消")
                connection = connect(uri)
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Accept-Encoding", "identity")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(++redirects <= 5) { "重定向过多" }
                    val location = connection.getHeaderField("Location") ?: error("缺少重定向地址")
                    val next = uri.resolve(location)
                    require(next.scheme == "https" && next.host != null && next.userInfo == null) { "拒绝不安全重定向" }
                    connection.disconnect()
                    uri = next
                    continue
                }
                require(status == 200) { "组件下载 HTTP $status" }
                require(connection.contentLengthLong <= pin.maxDownloadBytes) { "下载超过组件大小上限" }
                break
            }
            val hash = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection!!.inputStream.use { input ->
                staging.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw InterruptedException("下载已取消")
                        val count = input.read(buffer)
                        if (count < 0) break
                        total = Math.addExact(total, count.toLong())
                        require(total <= pin.maxDownloadBytes) { "下载超过组件大小上限" }
                        output.write(buffer, 0, count)
                        hash.update(buffer, 0, count)
                        progress(total)
                    }
                    output.fd.sync()
                }
            }
            require(total > 0 && hash.digest().toHex().equals(pin.sha256, true)) { "SHA-256 校验失败" }
            require(staging.renameTo(destination)) { "无法保存已校验组件" }
            return destination
        } finally {
            connection?.disconnect()
            staging.delete()
        }
    }

    companion object {
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            return hash.digest().toHex()
        }
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
