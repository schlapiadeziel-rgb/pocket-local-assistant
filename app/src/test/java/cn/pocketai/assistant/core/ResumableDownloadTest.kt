package cn.pocketai.assistant.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableDownloadTest {
    @get:Rule val folder = TemporaryFolder()
    private val data = "a complete test model".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private val size get() = data.size.toLong()
    private fun partial(prefix: Int = 0): File = folder.newFile().apply {
        writeBytes(data.take(prefix).toByteArray()); File(path + ".meta").writeText("$size:$hash")
    }
    private fun response(offset: Long, bytes: ByteArray = data.drop(offset.toInt()).toByteArray(), code: Int = if (offset == 0L) 200 else 206, range: String? = "bytes $offset-${size - 1}/$size") = object : DownloadResponse {
        override val code = code
        override val contentRange = range
        override val length = if (code == 206) size - offset else size
        override fun input() = ByteArrayInputStream(bytes)
        override fun close() {}
    }
    @Test fun shortConnectionResumesAtWrittenPrefix() {
        val file = partial(); val offsets = mutableListOf<Long>()
        ResumableDownload.fetch(file, size, hash, DownloadControl(), { offset ->
            offsets += offset
            if (offset == 0L) response(0, data.take(7).toByteArray()) else response(offset)
        })
        assertEquals(listOf(0L, 7L), offsets); assertArrayEquals(data, file.readBytes())
    }
    @Test fun serverIgnoringRangeRestartsWithoutDuplicatingBytes() {
        val file = partial(7)
        ResumableDownload.fetch(file, size, hash, DownloadControl(), { offset ->
            assertEquals(7L, offset); response(offset, data, code = 200)
        })
        assertArrayEquals(data, file.readBytes())
    }
    @Test fun wrongRangeCannotChangeExistingPrefix() {
        val file = partial(7)
        assertThrows(IllegalStateException::class.java) {
            ResumableDownload.fetch(file, size, hash, DownloadControl(), { response(it, range = "bytes 0-${size - 1}/$size") })
        }
        assertArrayEquals(data.take(7).toByteArray(), file.readBytes())
    }
    @Test fun differentArtifactDiscardsOldPrefix() {
        val file = partial(7); File(file.path + ".meta").writeText("$size:${"0".repeat(64)}")
        ResumableDownload.fetch(file, size, hash, DownloadControl(), { assertEquals(0L, it); response(it) })
        assertArrayEquals(data, file.readBytes())
    }
    @Test fun badHashDeletesUntrustedDownloadButLeavesInstalledFile() {
        val file = partial(); val installed = folder.newFile().apply { writeText("old working model") }
        assertThrows(IllegalStateException::class.java) {
            ResumableDownload.fetch(file, size, hash, DownloadControl(), { response(it, ByteArray(data.size)) })
        }
        assertFalse(file.exists()); assertFalse(File(file.path + ".meta").exists())
        assertEquals("old working model", installed.readText())
    }
    @Test fun pauseRetainsProgressAndNextOperationCanFinish() {
        val file = partial(); val control = DownloadControl()
        assertThrows(DownloadPaused::class.java) {
            ResumableDownload.fetch(file, size, hash, control, { response(it, data.take(7).toByteArray()) }) { if (it > 0) control.cancel() }
        }
        assertEquals(7L, ResumableDownload.partialBytes(file, size, hash))
        ResumableDownload.fetch(file, size, hash, DownloadControl(), { assertEquals(7L, it); response(it) })
        assertArrayEquals(data, file.readBytes())
    }
    @Test fun completePartialIsCheckedWithoutAnyNetworkRequest() {
        val file = partial(data.size)
        ResumableDownload.fetch(file, size, hash, DownloadControl(), { error("Network should not be used") })
        assertArrayEquals(data, file.readBytes())
    }
    @Test fun persistentNetworkFailureIsBoundedAndKeepsPrefix() {
        val file = partial(7); var calls = 0
        assertThrows(IOException::class.java) {
            ResumableDownload.fetch(file, size, hash, DownloadControl(), { calls++; throw IOException("offline") })
        }
        assertEquals(3, calls); assertEquals(7L, file.length())
    }
    @Test fun responseSizeMismatchIsRejectedBeforeOpeningBody() {
        val file = partial(7)
        assertThrows(IllegalStateException::class.java) {
            ResumableDownload.fetch(file, size, hash, DownloadControl(), { response(it, range = "bytes 7-${size - 1}/${size + 1}") })
        }
        assertEquals(7L, file.length())
    }
    @Test fun cancellationClosesAnActiveConnectionImmediately() {
        val control = DownloadControl(); var closed = false
        control.attach(java.io.Closeable { closed = true }); control.cancel()
        assertTrue(closed); assertThrows(DownloadPaused::class.java) { control.check() }
    }
}
