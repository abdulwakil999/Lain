package com.lain.assistant.network

import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Real web research: search, fetch the top pages, strip them to readable text, and
 * hand the agent the extracts plus their URLs so it can compare sources and cite
 * them.
 *
 * Uses DuckDuckGo's HTML endpoint — no API key, no account, and it degrades
 * honestly when unavailable. Deliberately distinct from "open a URL": that shows a
 * page to the user, this brings information back to the model.
 */
class WebResearch {

    private companion object {
        const val MAX_PAGES = 3
        const val MAX_CHARS_PER_PAGE = 2200
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun search(query: String, fetchPages: Boolean = true): ToolResult = withContext(Dispatchers.IO) {
        val hits = runCatching { duckDuckGo(query) }.getOrElse {
            return@withContext ToolResult.fail(
                FailureKind.NETWORK,
                "Web search failed — couldn't reach the search endpoint.",
                it.message
            )
        }

        if (hits.isEmpty()) {
            return@withContext ToolResult.fail(
                FailureKind.TOOL_FAILURE,
                "No results for \"$query\". Try different wording."
            )
        }

        if (!fetchPages) {
            return@withContext ToolResult.ok(
                hits.joinToString("\n") { "- ${it.title} — ${it.snippet} (${it.url})" },
                data = mapOf("sources" to hits.joinToString("\n") { it.url })
            )
        }

        // Fetch the top hits concurrently — sequential fetches make research feel broken.
        val pages = coroutineScope {
            hits.take(MAX_PAGES).map { hit ->
                async { hit to runCatching { extractText(hit.url) }.getOrNull() }
            }.map { it.await() }
        }

        val body = buildString {
            append("Search results for \"$query\":\n\n")
            pages.forEachIndexed { i, (hit, text) ->
                append("[${i + 1}] ${hit.title}\n")
                append("URL: ${hit.url}\n")
                if (!text.isNullOrBlank()) {
                    append("Extract: ").append(text.take(MAX_CHARS_PER_PAGE)).append("\n")
                } else {
                    append("Extract: (couldn't read this page — use the snippet) ${hit.snippet}\n")
                }
                append("\n")
            }
            val remaining = hits.drop(MAX_PAGES)
            if (remaining.isNotEmpty()) {
                append("Other results not fetched:\n")
                remaining.forEach { append("- ${it.title} (${it.url})\n") }
            }
            append("\nCite the URLs you actually used. If sources disagree, say so and say which you trust and why.")
        }

        ToolResult.ok(body, data = mapOf("sources" to pages.joinToString("\n") { it.first.url }))
    }

    suspend fun fetchPage(url: String): ToolResult = withContext(Dispatchers.IO) {
        val normalized = if (url.startsWith("http")) url else "https://$url"
        return@withContext try {
            val text = extractText(normalized)
            if (text.isNullOrBlank()) {
                ToolResult.fail(FailureKind.TOOL_FAILURE, "That page had no readable text (it may be JavaScript-only).")
            } else {
                ToolResult.ok("Content of $normalized:\n\n${text.take(6000)}", data = mapOf("sources" to normalized))
            }
        } catch (t: Throwable) {
            ToolResult.fail(FailureKind.NETWORK, "Couldn't fetch $normalized", t.message)
        }
    }

    private data class Hit(val title: String, val url: String, val snippet: String)

    private fun duckDuckGo(query: String): List<Hit> {
        val request = Request.Builder()
            .url("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"))
            .header("User-Agent", UA)
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            return parseHits(html)
        }
    }

    private fun parseHits(html: String): List<Hit> {
        val out = mutableListOf<Hit>()
        // Results are anchors with class result__a; snippets follow in result__snippet.
        val linkRe = Regex("""<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRe = Regex("""<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

        val links = linkRe.findAll(html).toList()
        val snippets = snippetRe.findAll(html).map { stripTags(it.groupValues[1]) }.toList()

        links.forEachIndexed { i, match ->
            val rawUrl = match.groupValues[1]
            val url = decodeRedirect(rawUrl)
            val title = stripTags(match.groupValues[2])
            if (title.isNotBlank() && url.startsWith("http")) {
                out += Hit(title, url, snippets.getOrElse(i) { "" }.take(300))
            }
        }
        return out.distinctBy { it.url }.take(6)
    }

    /** DuckDuckGo wraps results in a redirect; unwrap to the real destination. */
    private fun decodeRedirect(raw: String): String {
        val url = if (raw.startsWith("//")) "https:$raw" else raw
        val marker = "uddg="
        val idx = url.indexOf(marker)
        if (idx == -1) return url
        val encoded = url.substring(idx + marker.length).substringBefore("&")
        return runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(url)
    }

    private fun extractText(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val type = response.header("Content-Type").orEmpty()
            if (!type.contains("html", ignoreCase = true) && !type.contains("text", ignoreCase = true)) return null
            val html = response.body?.string() ?: return null
            return readable(html)
        }
    }

    /** Crude but dependency-free readability: drop non-content nodes, unwrap the rest. */
    private fun readable(html: String): String {
        var s = html
        listOf("script", "style", "noscript", "svg", "nav", "header", "footer", "form", "aside").forEach { tag ->
            s = s.replace(Regex("<$tag[^>]*>.*?</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        }
        s = s.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        // Keep block boundaries as line breaks so the extract stays readable.
        s = s.replace(Regex("</(p|div|li|h[1-6]|tr|br)>", RegexOption.IGNORE_CASE), "\n")
        return stripTags(s)
            .lines()
            .map { it.trim() }
            .filter { it.length > 2 }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
}
