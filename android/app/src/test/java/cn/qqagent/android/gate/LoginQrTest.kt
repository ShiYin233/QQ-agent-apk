package cn.qqagent.android.gate

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LoginQrTest {
    private val start = byteArrayOf(-119,80,78,71,13,10,26,10)
    private val end = byteArrayOf(73,69,78,68,-82,66,96,-126)
    private fun fixture(test: (File) -> Unit) {
        val home = Files.createTempDirectory("qqagent-qr-").toFile()
        try { test(home) } finally { home.deleteRecursively() }
    }
    private fun qr(home: File, path: String, body: Byte, at: Long): File = File(home, "rootfs/$path/qrcode.png").apply {
        parentFile.mkdirs(); writeBytes(start + byteArrayOf(body) + end); setLastModified(at)
    }
    @Test fun rewrittenImageWithSameTimestampIsDetected() = fixture { home ->
        val file = qr(home, "opt/napcat/cache", 1, 100000)
        val before = LoginQr.read(home)!!
        file.writeBytes(start + byteArrayOf(2) + end); file.setLastModified(100000)
        assertNotEquals(before.fingerprint, LoginQr.read(home)!!.fingerprint)
    }
    @Test fun latestPathWinsOverOldQr() = fixture { home ->
        qr(home, "opt/napcat/cache", 1, 100000)
        qr(home, "root/.config/QQ/cache", 2, 200000)
        assertEquals(2.toByte(), LoginQr.read(home)!!.bytes[8])
    }
    @Test fun partialNewestImageDoesNotFallBackToExpiredQr() = fixture { home ->
        qr(home, "opt/napcat/cache", 1, 100000)
        val next = qr(home, "root/.config/QQ/cache", 2, 200000)
        next.writeBytes(start); next.setLastModified(200000)
        assertNull(LoginQr.read(home))
        next.writeBytes(start + byteArrayOf(3) + end); next.setLastModified(200000)
        assertEquals(3.toByte(), LoginQr.read(home)!!.bytes[8])
    }
    @Test fun absentAndOversizedFilesAreHidden() = fixture { home ->
        assertNull(LoginQr.read(home))
        val file = qr(home, "opt/napcat/cache", 1, 100000)
        file.writeBytes(ByteArray(1024 * 1024 + 1))
        assertNull(LoginQr.read(home))
    }
}
