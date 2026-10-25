package cn.qqagent.android.gate

import android.system.Os
import java.io.File
import java.io.InputStream

/** Extract only a locked archive into a fresh staging directory. Links are last. */
object SafeTar {
    data class Link(val path: File, val target: String, val hard: Boolean)

    fun extract(input: InputStream, root: File, prefix: String, maxBytes: Long) {
        require(root.mkdirs() || root.isDirectory)
        require(root.listFiles().isNullOrEmpty()) { "Extraction must use an empty staging directory" }
        val base = root.canonicalFile
        val links = mutableListOf<Link>()
        val directories = mutableListOf<Pair<File, Int>>()
        var total = 0L
        var entries = 0
        var longName: String? = null
        var longLink: String? = null
        var pax = emptyMap<String, String>()
        val header = ByteArray(512)
        while (readBlock(input, header)) {
            if (header.all { it == 0.toByte() }) break
            require(++entries <= 250_000) { "Too many tar entries" }
            val recorded = number(header, 148, 8)
            val checksum = header.indices.sumOf { if (it in 148..155) 32 else header[it].toInt() and 255 }.toLong()
            require(recorded == checksum) { "Tar header checksum mismatch" }
            val rawName = text(header, 0, 100)
            val ustarPrefix = text(header, 345, 155)
            val type = header[156].toInt().toChar()
            val size = number(header, 124, 12)
            require(size >= 0 && size <= maxBytes)
            val mode = number(header, 100, 8).toInt() and 511
            if (type in listOf('L', 'K', 'x', 'g')) {
                require(size <= 1024 * 1024) { "Oversized tar metadata" }
                val metadata = ByteArray(size.toInt())
                readFully(input, metadata)
                skipFully(input, (512 - size % 512) % 512)
                val value = metadata.toString(Charsets.UTF_8).trimEnd('\u0000')
                when(type) {
                    'L' -> longName = value
                    'K' -> longLink = value
                    'x' -> pax = parsePax(value)
                    // Global metadata records owner/time; neither grants host permissions.
                    'g' -> require(!parsePax(value).containsKey("path"))
                }
                continue
            }
            val name = pax["path"] ?: longName ?: if (ustarPrefix.isEmpty()) rawName else "$ustarPrefix/$rawName"
            val linkTarget = pax["linkpath"] ?: longLink ?: text(header, 157, 100)
            longName = null; longLink = null; pax = emptyMap()
            val relative = stripPrefix(name, prefix)
            if (relative.isEmpty()) {
                skipFully(input, size + (512 - size % 512) % 512)
                continue
            }
            val path = resolve(base, relative)
            check(path.parentFile!!.mkdirs() || path.parentFile!!.isDirectory)
            when(type) {
                '0', '\u0000' -> {
                    total = Math.addExact(total, size)
                    require(total <= maxBytes) { "Archive exceeds installed space bound" }
                    require(!path.exists()) { "Duplicate archive path: $relative" }
                    path.outputStream().use { output -> copyExactly(input, output, size) }
                    Os.chmod(path.path, mode or 0x180) // owner read/write, retain execute bits
                }
                '5' -> { check(path.mkdirs() || path.isDirectory); directories += path to (mode or 0x1c0); skipFully(input, size) }
                '2', '1' -> { links += Link(path, linkTarget, type == '1'); skipFully(input, size) }
                '3', '4', '6' -> skipFully(input, size) // /dev is bound from Android, no mknod/root
                else -> error("Unsupported tar entry type: $type")
            }
            skipFully(input, (512 - size % 512) % 512)
        }
        // File writes precede all symlinks, so archive paths cannot redirect writes.
        links.filter { it.hard }.forEach { link ->
            val source = resolve(base, stripPrefix(link.target, prefix))
            require(source.isFile && !link.path.exists()) { "Invalid tar hard link" }
            total = Math.addExact(total, source.length())
            require(total <= maxBytes) { "Hard links exceed installed space bound" }
            // Android disallows link(2) for app UIDs on some ROMs; copy identical data.
            source.copyTo(link.path)
            Os.chmod(link.path.path, (Os.stat(source.path).st_mode and 511) or 0x180)
        }
        links.filter { !it.hard }.forEach { link ->
            require(!link.path.exists())
            require(link.target.isNotBlank() && '\u0000' !in link.target)
            // Absolute guest links (/proc/self/fd etc.) are translated by PRoot.
            Os.symlink(link.target, link.path.path)
        }
        directories.asReversed().forEach { (file, mode) -> Os.chmod(file.path, mode) }
    }

    internal fun stripPrefix(name: String, prefix: String): String {
        val normalized = name.removePrefix("./").trimEnd('/')
        require('\u0000' !in normalized && !normalized.startsWith('/') && normalized.split('/').none { it == ".." }) { "Unsafe tar path" }
        return if (prefix.isEmpty()) normalized else {
            require(normalized == prefix || normalized.startsWith("$prefix/")) { "Wrong rootfs prefix" }
            normalized.removePrefix(prefix).removePrefix("/")
        }
    }
    private fun resolve(base: File, relative: String): File {
        val file = File(base, relative).canonicalFile
        require(file.path.startsWith(base.path + File.separator)) { "Tar path escapes staging" }
        return file
    }
    private fun text(buf: ByteArray, offset: Int, len: Int) = buf.copyOfRange(offset, offset+len).toString(Charsets.UTF_8).trimEnd('\u0000')
    private fun number(buf: ByteArray, offset: Int, len: Int): Long {
        require(buf[offset].toInt() and 128 == 0) { "Unsupported binary tar number" }
        val value = text(buf, offset, len).trim().trim('\u0000')
        return if (value.isEmpty()) 0 else value.toLong(8)
    }
    private fun readBlock(input: InputStream, buf: ByteArray): Boolean {
        val first = input.read()
        if (first < 0) return false
        buf[0] = first.toByte()
        var offset = 1
        while(offset < buf.size) { val n=input.read(buf,offset,buf.size-offset); require(n>0) { "Truncated tar header" }; offset+=n }
        return true
    }
    private fun readFully(input: InputStream, buf: ByteArray) {
        var offset=0
        while(offset<buf.size) { val n=input.read(buf,offset,buf.size-offset); require(n>0) { "Truncated tar entry" }; offset+=n }
    }
    private fun skipFully(input: InputStream, bytes: Long) {
        var left=bytes
        val buf=ByteArray(65536)
        while(left>0) { val n=input.read(buf,0,minOf(left,buf.size.toLong()).toInt()); require(n>0) { "Truncated tar payload" }; left-=n }
    }
    private fun copyExactly(input: InputStream, output: java.io.OutputStream, bytes: Long) {
        var left=bytes; val buf=ByteArray(65536)
        while(left>0) { val n=input.read(buf,0,minOf(left,buf.size.toLong()).toInt()); require(n>0) { "Truncated tar payload" }; output.write(buf,0,n); left-=n }
    }
    private fun parsePax(text: String): Map<String,String> {
        val result=mutableMapOf<String,String>()
        // Paths in the pinned archives are UTF-8; lengths in PAX count bytes.
        val bytes=text.toByteArray(Charsets.UTF_8); var offset=0
        while(offset<bytes.size) {
            val space=(offset until bytes.size).first { bytes[it]==32.toByte() }
            val length=bytes.copyOfRange(offset,space).toString(Charsets.US_ASCII).toInt()
            require(length>space-offset+1 && offset+length<=bytes.size)
            val record=bytes.copyOfRange(space+1,offset+length).toString(Charsets.UTF_8).trimEnd('\n')
            val equal=record.indexOf('='); require(equal>0)
            result[record.substring(0,equal)]=record.substring(equal+1); offset+=length
        }
        return result
    }
}
