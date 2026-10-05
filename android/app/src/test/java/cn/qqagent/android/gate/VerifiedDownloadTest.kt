package cn.qqagent.android.gate

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class VerifiedDownloadTest {
    private val content = "verified component".toByteArray()
    private fun directory() = Files.createTempDirectory("qq-agent-artifact-test").toFile()
    private fun pin(directory: File): ComponentPin {
        val fixture = File(directory, "fixture").apply { writeBytes(content) }
        val hash = VerifiedDownload.digest(fixture)
        fixture.delete()
        return ComponentPin("node", "1.0", "https://example.com/component", hash, "test", "fixture", 100, 100)
    }
    private class Response(
        val content: ByteArray, val status: Int = 200, val redirect: String? = null,
        val length: Long = content.size.toLong(),
    ) : HttpURLConnection(URL("https://example.com/component")) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getInputStream() = ByteArrayInputStream(content)
        override fun getHeaderField(name: String?) = if (name == "Location") redirect else null
    }

    @Test fun successfulArtifactIsVerifiedAndCanBeReusedWithoutNetwork() {
        val dir = directory()
        try {
            val pin = pin(dir)
            val file = VerifiedDownload { Response(content) }.fetch(pin, dir)
            assertArrayEquals(content, file.readBytes())
            assertEquals(file, VerifiedDownload { error("must not download verified cache") }.fetch(pin, dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidHashLeavesNoArtifactOrPartialFile() {
        val dir = directory()
        try {
            val pin = pin(dir)
            assertTrue(runCatching { VerifiedDownload { Response("tampered".toByteArray()) }.fetch(pin, dir) }.isFailure)
            assertEquals(0, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun unknownLengthCannotBypassSizeLimit() {
        val dir = directory()
        try {
            val pin = pin(dir).copy(maxDownloadBytes = 1)
            assertTrue(runCatching { VerifiedDownload { Response(content, length = -1) }.fetch(pin, dir) }.isFailure)
            assertEquals(0, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun insecureRedirectAndHttpFailureCannotCreateArtifacts() {
        val dir = directory()
        try {
            val pin = pin(dir)
            for (response in listOf(Response(content, 302, "http://example.com/a"), Response(content, 503))) {
                assertTrue(runCatching { VerifiedDownload { response }.fetch(pin, dir) }.isFailure)
                assertEquals(0, dir.listFiles()!!.size)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellationPreservesExistingArtifactsAndCleansStaging() {
        val dir = directory()
        try {
            val pin = pin(dir)
            val previous = File(dir, "previous").apply { writeText("old verified runtime") }
            var calls = 0
            assertTrue(runCatching { VerifiedDownload { Response(content) }.fetch(pin, dir, { ++calls > 1 }) }.isFailure)
            assertEquals("old verified runtime", previous.readText())
            assertEquals(listOf(previous), dir.listFiles()!!.toList())
        } finally { dir.deleteRecursively() }
    }
}
