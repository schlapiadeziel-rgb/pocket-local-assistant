package cn.pocketai.assistant

import cn.pocketai.assistant.core.DownloadPaused
import cn.pocketai.assistant.core.DownloadResponse
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

object DownloadNetwork {
    fun open(url: String, offset: Long, github: Boolean = false): DownloadResponse {
        require(URL(url).protocol == "https")
        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "PocketLocalAssistant/0.1")
            if (github) setRequestProperty("Accept", "application/octet-stream")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        return object : DownloadResponse {
            override val code: Int get() {
                val result = connection.responseCode
                check(connection.url.protocol == "https") { "下载链接必须使用 HTTPS" }
                return result
            }
            override val contentRange: String? get() = connection.getHeaderField("Content-Range")
            override val length: Long get() = connection.contentLengthLong
            override fun input() = connection.inputStream
            override fun close() = connection.disconnect()
        }
    }
    fun explain(error: Exception): String = when (error) {
        is DownloadPaused -> "下载已暂停，进度已保留，下次点击下载可继续"
        is SocketTimeoutException -> "网络连接超时，进度已保留；请检查网络后重试"
        is UnknownHostException -> "无法解析下载站地址，请检查网络后重试"
        is SSLException -> "无法建立安全连接，请检查网络和系统时间后重试"
        is java.net.ConnectException -> "暂时无法连接下载站，请检查网络后重试"
        is java.io.IOException -> "网络或文件读写中断，已有下载进度会保留，请重试"
        else -> error.message?.take(160) ?: "操作未完成，请重试"
    }
}
