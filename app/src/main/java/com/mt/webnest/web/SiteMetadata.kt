package com.mt.webnest.web

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream

object SiteMetadata {
    data class Result(val name: String, val icon: ByteArray?, val titleFound: Boolean, val themeColor: Int? = null)
    data class Document(val title: String?, val icons: List<String>, val manifest: String? = null, val themeColor: Int? = null)
    data class Manifest(val name: String?, val icons: List<String>, val themeColor: Int? = null)
    private val attributes = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val tags = Regex("""<(link|meta|base)\b(?:[^>"']|"[^"]*"|'[^']*')*>""", RegexOption.IGNORE_CASE)

    fun parse(html: String, pageUrl: String): Document {
        fun attrs(tag: String) = attributes.findAll(tag).associate { it.groupValues[1].lowercase() to it.groupValues.drop(2).firstOrNull { value -> value.isNotEmpty() }.orEmpty().replace("&amp;", "&")}
        val elements = tags.findAll(html).map { it.groupValues[1].lowercase() to attrs(it.value) }.toList()
        val base = elements.firstOrNull { it.first == "base" }?.second?.get("href") ?.let { runCatching { URI(pageUrl).resolve(it) }.getOrNull() } ?: URI(pageUrl)
        fun resolve(value: String): String? = resolveAsset(base.toString(), value)
        val rawTitle = Regex("<title\\b[^>]*>(.*?)</title\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        val meta = elements.filter { it.first == "meta" }.associate { (_, values) -> (values["property"] ?: values["name"]).orEmpty().lowercase() to values["content"].orEmpty()}
        val title = rawTitle ?: listOf("application-name", "og:site_name", "og:title", "twitter:title").firstNotNullOfOrNull { meta[it]?.takeIf(String::isNotBlank) }
        val links = elements.filter { it.first == "link" }
        val icons = links.mapNotNull { (_, values) ->
            val rel = values["rel"].orEmpty().lowercase().split(Regex("\\s+"))
            if (rel.none { it == "icon" || it.startsWith("apple-touch-icon") }) return@mapNotNull null
            val url = values["href"]?.let(::resolve) ?: return@mapNotNull null
            url to (iconSize(values["sizes"]) ?: if (rel.any { it.startsWith("apple-touch-icon") }) 180 else 16)
        }.sortedByDescending { it.second }.map { it.first }.distinct().take(6)
        val manifest = links.firstNotNullOfOrNull { (_, values) -> if (values["rel"].orEmpty().lowercase().split(Regex("\\s+")).contains("manifest")) values["href"]?.let(::resolve)?.takeUnless { it.startsWith("data:")} else null }
        val themeColor = elements.filter { it.first == "meta" && it.second["name"].equals("theme-color", true)}.sortedBy { if (it.second["media"].isNullOrBlank()) 0 else 1 }.firstNotNullOfOrNull { SiteColor.parse(it.second["content"])}
        return Document(title, icons, manifest, themeColor)
    }

    fun parseManifest(json: String, manifestUrl: String): Manifest {
        val document = JSONObject(json.removePrefix("\uFEFF"))
        val name = listOf("short_name", "name").firstNotNullOfOrNull { key -> document.optString(key).takeIf { !document.isNull(key) && it.isNotBlank() } }
        val list = document.optJSONArray("icons")
        val icons = (0 until (list?.length() ?: 0)).mapNotNull { index ->
            val icon = list?.optJSONObject(index) ?: return@mapNotNull null
            val url = resolveAsset(manifestUrl, icon.optString("src")) ?: return@mapNotNull null
            url to (iconSize(icon.optString("sizes")) ?: 16)
        }.sortedByDescending { it.second }.map { it.first }.distinct().take(6)
        return Manifest(name, icons, SiteColor.parse(document.optString("theme_color")))
    }

    private fun iconSize(value: String?): Int? = value?.split(Regex("\\s+"))?.mapNotNull {
        it.substringBefore('x').toIntOrNull()?.coerceAtMost(512)
    }?.maxOrNull()

    private fun resolveAsset(base: String, value: String): String? {
        if (value.isBlank()) return null
        if (value.startsWith("data:image/") && value.contains(";base64,") && value.length < 1_400_000) return value
        return runCatching { WebUrls.normalize(URI(base).resolve(value).toString()) }.getOrNull()
    }

    suspend fun fetch(url: String, userAgent: String = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"): Result = withContext(Dispatchers.IO) {
        require(WebUrls.normalize(url) != null)
        val deadline = System.nanoTime() + 20_000_000_000L
        var finalUrl = url
        var document = Document(null, emptyList())
        fun readDocument(target: String) {
            val page = request(target, 1024 * 1024, deadline, userAgent, 5000)
            finalUrl = page.url
            val charset = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(page.contentType.orEmpty())
                ?.groupValues?.get(1)?.trim('"', '\'')?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
            document = parse(page.bytes.toString(charset), finalUrl)
        }
        runCatching { readDocument(url)}
        val root = URI(finalUrl).resolve("/").toString()
        if (document.title == null && document.icons.isEmpty() && document.manifest == null && root != url) {
            runCatching { readDocument(root)}
        }
        val manifest = document.manifest?.let { target ->
            runCatching {
                val response = request(target, 256 * 1024, deadline, userAgent, 3000)
                parseManifest(response.bytes.toString(Charsets.UTF_8), response.url)
            }.getOrNull()
        }
        val rawName = manifest?.name ?: document.title
        val name = rawName?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim().take(100) }
        val fallbacks = listOf("/favicon.ico", "/apple-touch-icon.png", "/favicon.png").map { URI(finalUrl).resolve(it).toString()}
        val advertised = (manifest?.icons.orEmpty() + document.icons).distinct().take(7)
        val candidates = (advertised + fallbacks).distinct()
        val decoded = candidates.map { iconUrl -> async {
            runCatching {
                val bytes = if (iconUrl.startsWith("data:")) java.util.Base64.getDecoder().decode(iconUrl.substringAfter(','))
                    else request(iconUrl, SiteIcons.MAX_BYTES, deadline, userAgent, 3000).bytes
                SiteIcons.normalize(bytes)
            }.getOrNull()
        } }.awaitAll()
        Result(name?.takeIf(String::isNotBlank) ?: WebUrls.host(url), decoded.firstOrNull { it != null }, !name.isNullOrBlank(), manifest?.themeColor ?: document.themeColor)
    }

    private data class Response(val bytes: ByteArray, val url: String, val contentType: String?)
    private fun request(input: String, limit: Int, deadline: Long, userAgent: String, timeout: Int): Response {
        var current = input
        val requestDeadline = minOf(deadline, System.nanoTime() + timeout * 1_000_000L)
        repeat(6) {
            val remaining = ((requestDeadline - System.nanoTime()) / 1_000_000).toInt()
            check(remaining > 0) { "Metadata timed out" }
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = remaining
                connection.readTimeout = remaining
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "text/html,application/manifest+json,application/json,image/*,*/*;q=0.8")
                connection.setRequestProperty("Accept-Encoding", "gzip")
                when (connection.responseCode) {
                    in 300..399 -> {
                        val target = connection.getHeaderField("Location") ?: error("Missing redirect")
                        current = WebUrls.normalize(URI(current).resolve(target).toString()) ?: error("Invalid redirect")
                    }
                    in 200..299 -> {
                        val stream = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(connection.inputStream) else connection.inputStream
                        val bytes = stream.use {
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (output.size() < limit) {
                                check(System.nanoTime() < requestDeadline) { "Response timed out" }
                                val read = it.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        }
                        return Response(bytes, current, connection.contentType)
                    }
                    else -> error("HTTP ${connection.responseCode}")
                }
            } finally { connection.disconnect() }
        }
        error("Too many redirects")
    }
}
