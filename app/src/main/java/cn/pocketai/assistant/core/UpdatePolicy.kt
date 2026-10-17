package cn.pocketai.assistant.core

import java.net.URI

object UpdatePolicy {
    const val REPOSITORY = "schlapiadeziel-rgb/pocket-local-assistant"
    const val API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val PAGE = "https://github.com/$REPOSITORY/releases/latest"
    fun validAssetUrl(value: String, expectedFile: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.startsWith("/$REPOSITORY/releases/download/") &&
            uri.rawPath.substringAfter("/$REPOSITORY/releases/download/").split('/').let { it.size == 2 && it[0].matches(Regex("v[0-9A-Za-z.\\-]+")) && it[1] == expectedFile }
    }.getOrDefault(false)
    fun validDigest(value: String) = value.matches(Regex("[a-f0-9]{64}"))
}
