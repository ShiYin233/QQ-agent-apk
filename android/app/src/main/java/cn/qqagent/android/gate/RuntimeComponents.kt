package cn.qqagent.android.gate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** Installs hash-pinned guest files inside this application's private directory. */
class RuntimeComponents(private val context: Context) {
    private val home = File(context.filesDir, "runtime")
    private val downloads = File(context.filesDir, "component-downloads")

    fun prepareCore(root: File) {
        val pin = JSONObject(context.assets.open("runtime/agent-core.json").bufferedReader().use { it.readText() })
        check(downloads.mkdirs() || downloads.isDirectory)
        val archive = File(downloads, "agent-core.zip")
        if (!archive.isFile || archive.length() != pin.getLong("size") || digest(archive) != pin.getString("sha256")) {
            val stage = File.createTempFile("core-", ".partial", downloads)
            try {
                context.assets.open("runtime/agent-core.zip").use { input -> stage.outputStream().use { input.copyTo(it) } }
                require(stage.length() == pin.getLong("size") && digest(stage) == pin.getString("sha256"))
                check(stage.renameTo(archive))
            } finally { stage.delete() }
        }
        installZip(pin, File(root, "opt/agent"))
    }

    fun prepareQQ(root: File) {
        val pins = JSONArray(context.assets.open("runtime/component-manifest.json").bufferedReader().use { it.readText() })
        verifyArchive(pin(pins, "linux-qq"))
        val napcat = pin(pins, "napcat")
        val target = File(root, "opt/napcat")
        installZip(napcat, target)
    }

    private fun installZip(napcat: JSONObject, target: File) {
        val marker = File(target, ".qqagent-component.json")
        if (marker.isFile && JSONObject(marker.readText()).optString("sha256") == napcat.getString("sha256")) return
        check(!target.exists()) { "Preserve incomplete NapCat for diagnosis" }
        val archive = verifyArchive(napcat)
        val staging = File(target.parentFile, ".${target.name}-${UUID.randomUUID()}")
        check(staging.mkdirs())
        var total = 0L
        var count = 0
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 50_000)
                val relative = SafeTar.stripPrefix(entry.name, "")
                require(relative.isNotEmpty())
                val file = File(staging, relative).canonicalFile
                require(file.path.startsWith(staging.canonicalPath + File.separator))
                if (entry.isDirectory) check(file.mkdirs() || file.isDirectory) else {
                    check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                    require(!file.exists()) { "Duplicate Zip path" }
                    file.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = zip.read(buffer); if (n < 0) break
                            total = Math.addExact(total, n.toLong())
                            require(total <= 512L * 1024 * 1024) { "NapCat exceeds space bound" }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        File(staging, ".qqagent-component.json").writeText(napcat.toString(2))
        check(staging.renameTo(target))
    }

    private fun verifyArchive(pin: JSONObject): File {
        val filename = pin.getString("filename")
        require(filename.matches(Regex("[A-Za-z0-9_.-]+")))
        val archive = File(downloads, filename)
        require(archive.isFile && archive.length() == pin.getLong("size") && digest(archive) == pin.getString("sha256")) {
            "Missing or invalid component: $filename"
        }
        return archive
    }

    fun installDebianAndNode(): File {
        val pins = JSONArray(context.assets.open("runtime/component-manifest.json").bufferedReader().use { it.readText() })
        val debian = pin(pins, "debian")
        val node = pin(pins, "node")
        val root = install(debian, "rootfs", "debian-trixie-aarch64", 2L * 1024 * 1024 * 1024)
        val nodeHome = install(node, "node", "node-v22.20.0-linux-arm64", 512L * 1024 * 1024)
        val opt = File(root, "opt")
        check(opt.mkdirs() || opt.isDirectory)
        // Bind the separately managed, verified Node tree at /opt/node.
        check(nodeHome.isDirectory)
        return root
    }

    private fun pin(pins: JSONArray, id: String): JSONObject =
        (0 until pins.length()).map { pins.getJSONObject(it) }.single { it.getString("id") == id }

    private fun install(pin: JSONObject, directory: String, prefix: String, limit: Long): File {
        val target = File(home, directory)
        val marker = File(target, ".qqagent-component.json")
        val expected = pin.getString("sha256")
        if (marker.isFile && JSONObject(marker.readText()).optString("sha256") == expected) return target
        check(!target.exists()) { "A different or incomplete $directory exists; preserve it for diagnosis" }
        val filename = pin.getString("filename")
        require(filename.matches(Regex("[A-Za-z0-9_.-]+")))
        val archive = File(downloads, filename)
        require(archive.isFile) { "Missing verified download: $filename" }
        require(archive.length() == pin.getLong("size") && digest(archive) == expected) { "Component hash mismatch: $filename" }
        require(context.filesDir.usableSpace > limit + 256L * 1024 * 1024) { "Insufficient extraction space" }
        val staging = File(home, ".install-$directory-${UUID.randomUUID()}")
        check(staging.mkdirs())
        // Preserve failed staging files for diagnosis; never replace a working component.
        archive.inputStream().buffered().use { source ->
            XZInputStream(source, 128 * 1024).use { SafeTar.extract(it, staging, prefix, limit) }
        }
        File(staging, ".qqagent-component.json").writeText(pin.toString(2))
        check(staging.renameTo(target)) { "Component commit failed" }
        return target
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
