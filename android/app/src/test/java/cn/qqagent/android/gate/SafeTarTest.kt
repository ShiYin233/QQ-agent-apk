package cn.qqagent.android.gate

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class SafeTarTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun acceptsPinnedRootAndRelativePath() {
        assertEquals("usr/bin/sh", SafeTar.stripPrefix("debian-trixie-aarch64/usr/bin/sh", "debian-trixie-aarch64"))
        assertEquals("", SafeTar.stripPrefix("./debian-trixie-aarch64/", "debian-trixie-aarch64"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTraversal() { SafeTar.stripPrefix("root/usr/../../other", "root") }
    @Test(expected = IllegalArgumentException::class) fun rejectsAbsoluteHostPath() { SafeTar.stripPrefix("/data/data/other/file", "") }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnexpectedArchiveRoot() { SafeTar.stripPrefix("other/usr/bin/sh", "root") }
    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedTarHeader() {
        SafeTar.extract(ByteArrayInputStream(byteArrayOf(1, 2, 3)), temporary.newFolder(), "root", 1024)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsCorruptTarChecksumBeforeWriting() {
        val header = header("root/file", 0)
        header[0] = 'x'.code.toByte()
        SafeTar.extract(ByteArrayInputStream(header), temporary.newFolder(), "root", 1024)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTraversalInValidTarHeader() {
        SafeTar.extract(ByteArrayInputStream(header("root/../../outside", 0)), temporary.newFolder(), "root", 1024)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizedEntryBeforeReadingPayload() {
        SafeTar.extract(ByteArrayInputStream(header("root/file", 2048)), temporary.newFolder(), "root", 1024)
    }
    private fun header(name: String, size: Long): ByteArray {
        val block = ByteArray(512)
        name.toByteArray().copyInto(block)
        "0000644\u0000".toByteArray().copyInto(block, 100)
        (size.toString(8).padStart(11, '0') + '\u0000').toByteArray().copyInto(block, 124)
        for (i in 148..155) block[i] = 32
        block[156] = '0'.code.toByte()
        (block.sumOf { it.toInt() and 255 }.toString(8).padStart(6, '0') + "\u0000 ").toByteArray().copyInto(block, 148)
        return block
    }
}
