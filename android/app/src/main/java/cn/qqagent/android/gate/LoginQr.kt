package cn.qqagent.android.gate

import java.io.File
import java.security.MessageDigest

data class LoginQrSnapshot(val fingerprint: String, val bytes: ByteArray)

/** Read bounded, stable contents; timestamps alone do not identify a QR refresh. */
object LoginQr {
    fun files(home: File): List<File> {
        val root = File(home, "rootfs").canonicalFile
        return listOf(File(root, "opt/napcat"), File(root, "root/.config/QQ"))
            .filter { it.isDirectory }.flatMap { folder -> folder.walkTopDown().maxDepth(8).take(20_000)
                .filter { it.name == "qrcode.png" && it.isFile && it.canonicalPath.startsWith(root.path + File.separator) }.toList() }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.path })
    }
    fun read(home: File): LoginQrSnapshot? {
        val file = files(home).firstOrNull() ?: return null
        return runCatching {
            val modified = file.lastModified(); val size = file.length()
            if (size !in 1..1024 * 1024) return null
            val bytes = file.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 1024 * 1024) {
                    val count = input.read(buffer); if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size.toLong() != size || file.length() != size || file.lastModified() != modified) return null
            val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
            val end = byteArrayOf(73, 69, 78, 68, -82, 66, 96, -126)
            if (!bytes.take(8).toByteArray().contentEquals(png) || !bytes.takeLast(8).toByteArray().contentEquals(end)) return null
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            LoginQrSnapshot(file.canonicalPath + ":" + digest, bytes)
        }.getOrNull()
    }
}
