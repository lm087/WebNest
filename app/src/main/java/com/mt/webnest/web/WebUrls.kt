package com.mt.webnest.web

import java.net.IDN
import java.net.URI
import java.util.Locale

object WebUrls {
    fun normalize(input: String): String? = runCatching {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() || it.isISOControl() }) return null
        val raw = if (text.contains("://")) text else "https://$text"
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val authority = uri.rawAuthority ?: return null
        if (authority.contains('@') || authority.contains('\\')) return null
        val asciiAuthority =
            if (authority.startsWith("[")) authority
            else {
                val host = authority.substringBefore(':')
                IDN.toASCII(host) + authority.removePrefix(host)
            }
        val normalized =
            URI(
                "$scheme://$asciiAuthority${uri.rawPath.orEmpty()}" +
                    (uri.rawQuery?.let { "?$it" } ?: "") +
                    (uri.rawFragment?.let { "#$it" } ?: "")
            )
        val host = normalized.host ?: return null
        if (host.isBlank() || normalized.port !in -1..65535 || normalized.port == 0) return null
        normalized.toASCIIString()
    }
        .getOrNull()

    fun fromSharedText(text: String): String? {
        val match = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE).find(text)?.value
        if (match != null) {
            var candidate = match.trimEnd('.', ',', ';', '!', '。', '，', '；', '！', '」', '』')
            while (
                candidate.endsWith(')') &&
                    candidate.count { it == ')' } > candidate.count { it == '(' }
            ) {
                candidate = candidate.dropLast(1)
            }
            return normalize(candidate)
        }
        return normalize(text)
    }

    fun host(url: String): String = runCatching {
        URI(url).host.removePrefix("www.")
    }
        .getOrDefault(url)
}
