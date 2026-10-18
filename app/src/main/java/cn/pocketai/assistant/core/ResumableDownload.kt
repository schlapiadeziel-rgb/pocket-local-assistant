package cn.pocketai.assistant.core

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

class DownloadPaused : IOException("下载已暂停，进度已保留")

/** Each operation owns its cancellation handle, including a blocked network read. */
class DownloadControl {
    @Volatile var stopped = false
        private set
    private var active: Closeable? = null
    @Synchronized fun attach(value: Closeable) {
        if (stopped) { value.close(); throw DownloadPaused() }
        active = value
    }
    @Synchronized fun detach(value: Closeable) { if (active === value) active = null }
    fun cancel() {
        val connection = synchronized(this) { stopped = true; active.also { active = null } }
        try { connection?.close() } catch (_: IOException) {}
    }
    fun check() { if (stopped) throw DownloadPaused() }
}

interface DownloadResponse : Closeable {
    val code: Int
    val contentRange: String?
    val length: Long
    fun input(): java.io.InputStream
}

/** Retains only a prefix bound to the expected hash and size. Never promotes unchecked bytes. */
object ResumableDownload {
    fun partialBytes(file: File, size: Long, sha256: String): Long =
        if (File(file.path + ".meta").readTextOrEmpty() == "$size:$sha256" && file.length() <= size) file.length() else 0

    private fun File.readTextOrEmpty() = try { if (isFile) readText() else "" } catch (_: IOException) { "" }
    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun fetch(
        file: File, size: Long, sha256: String, control: DownloadControl,
        source: (Long) -> DownloadResponse, progress: (Long) -> Unit = {}
    ) {
        require(size > 0 && sha256.matches(Regex("[0-9a-f]{64}")))
        val meta = File(file.path + ".meta")
        file.parentFile?.mkdirs()
        if (partialBytes(file, size, sha256) == 0L) {
            FileOutputStream(file).use { it.fd.sync() }
            meta.writeText("$size:$sha256")
        }
        var failures = 0
        while (file.length() < size) {
            control.check()
            val offset = file.length()
            progress(offset)
            var response: DownloadResponse? = null
            try {
                response = source(offset)
                control.attach(response)
                val code = response.code
                if (code == 429 || code in 500..599) throw IOException("下载服务暂时不可用（HTTP $code）")
                check(code == 200 || code == 206) { "下载服务返回 HTTP $code" }
                val append = code == 206
                if (append) {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.contentRange.orEmpty())
                    check(range != null) { "下载续传范围缺失，已保留进度" }
                    val (start, end, total) = range.destructured
                    check(start.toLong() == offset && total.toLong() == size && end.toLong() in offset until size) { "下载续传范围不匹配，已保留进度" }
                    check(response.length < 0 || response.length == end.toLong() - offset + 1) { "下载长度与续传范围不匹配" }
                } else {
                    check(response.length < 0 || response.length == size) { "下载文件大小不符合预期" }
                }
                // A server may ignore Range. A full response must replace, never append to, the prefix.
                response.input().use { input ->
                    FileOutputStream(file, append).use { output ->
                        var count = if (append) offset else 0L
                        val buffer = ByteArray(256 * 1024)
                        try {
                            while (true) {
                                control.check()
                                val n = input.read(buffer); if (n < 0) break
                                control.check()
                                if (count + n > size) {
                                    file.delete(); meta.delete()
                                    error("下载内容超出预期大小，已丢弃异常文件")
                                }
                                output.write(buffer, 0, n); count += n; progress(count)
                            }
                        } finally { output.fd.sync() }
                        if (count != size) throw IOException("下载连接中断，进度已保留")
                    }
                }
            } catch (e: IOException) {
                control.check()
                if (++failures >= 3) throw e
            } finally {
                response?.let { control.detach(it); try { it.close() } catch (_: IOException) {} }
            }
        }
        control.check()
        if (hash(file) != sha256) {
            file.delete(); meta.delete()
            error("SHA-256 校验不通过，已丢弃异常文件，现有文件未替换")
        }
        control.check()
    }
    fun forgetMetadata(file: File) { File(file.path + ".meta").delete() }
}
