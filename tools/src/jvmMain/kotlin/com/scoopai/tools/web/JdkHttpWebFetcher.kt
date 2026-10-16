package com.scoopai.tools.web

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validateNotNull
import com.scoopai.core.agent.tools.ToolCallBudget
import com.scoopai.core.tools.api.WebFetcher
import com.scoopai.tools.http.RetryingHttpClient
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@LLMDescription("Web fetching tools for reading web pages and APIs")
class JdkHttpWebFetcher(
    /** Optional per-run budget to limit expensive tool calls. */
    private val budget: ToolCallBudget? = null,
) : WebFetcher, ToolSet {

    private val httpClient = RetryingHttpClient()

    // ---------------------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------------------

    private fun httpGetString(url: String): String {
        val response = httpClient.get(url, mapOf(
            "User-Agent" to "scoop-ai/0.1.0",
        ))
        return response.body()
    }

    // ---------------------------------------------------------------------------
    // HTML content extraction (for fetchWebPage)
    // ---------------------------------------------------------------------------

    /**
     * Extracts visible text content from HTML by removing script, style, noscript
     * blocks, HTML comments, tags, and decoding common entities.
     * Safe to call on non-HTML content (passes through unchanged).
     */
    internal fun extractMainContent(html: String): String {
        var text = html
        // Remove script and style blocks (with content)
        text = text.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<noscript[^>]*>[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), "")
        // Remove HTML comments
        text = text.replace(Regex("<!--[\\s\\S]*?-->"), "")
        // Remove all HTML tags
        text = text.replace(Regex("<[^>]+>"), " ")
        // Decode common HTML entities
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        // Compress whitespace
        text = text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
        return text
    }

    // ---------------------------------------------------------------------------
    // Tool implementations
    // ---------------------------------------------------------------------------

    /**
     * Check the tool call budget for [toolName].
     * @return the budget-exhausted message if the budget is exhausted, or `null` if the call may proceed.
     */
    private fun checkBudget(toolName: String, limit: Int): String? {
        val b = budget ?: return null
        return if (b.tryAcquire(toolName, limit)) null else ToolCallBudget.exhaustedMessage(toolName, limit)
    }

    @Tool
    @LLMDescription("Fetch a web page and return its text content (HTML stripped)")
    override suspend fun fetchWebPage(
        @LLMDescription("The URL of the web page to fetch")
        url: String?
    ): String {
        val urlNotNull = validateNotNull(url) { "Parameter 'url' is required. Provide the URL of the web page to fetch." }
        checkBudget("fetchWebPage", ToolCallBudget.FETCH_WEB_PAGE_LIMIT)?.let { return it }
        return try {
            val body = httpGetString(urlNotNull)
            // For HTML content, extract main text before truncation
            val text = if (body.trimStart().startsWith("<")) {
                extractMainContent(body)
            } else {
                // Non-HTML (JSON, etc.): just strip any accidental HTML, collapse whitespace
                body.replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            if (text.length > 5000) text.substring(0, 5000) else text
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to fetch web page: ${e.message}")
            }.toString()
        }
    }

    @Tool
    @LLMDescription("Fetch a URL and return the raw JSON response")
    override suspend fun fetchJson(
        @LLMDescription("The URL to fetch JSON from")
        url: String?
    ): String {
        val urlNotNull = validateNotNull(url) { "Parameter 'url' is required. Provide the URL to fetch JSON from." }
        return try {
            val body = httpGetString(urlNotNull)
            if (body.length > 10000) body.substring(0, 10000) else body
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to fetch JSON: ${e.message}")
            }.toString()
        }
    }

    @Tool
    @LLMDescription("Follow HTTP redirects and return the final resolved URL")
    override suspend fun resolveRedirect(
        @LLMDescription("The URL that may redirect")
        url: String?
    ): String {
        val urlNotNull = validateNotNull(url) { "Parameter 'url' is required. Provide the URL that may redirect." }
        checkBudget("resolveRedirect", ToolCallBudget.RESOLVE_REDIRECT_LIMIT)?.let { return it }
        return try {
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build()
            val request = HttpRequest.newBuilder()
                .uri(URI.create(urlNotNull))
                .header("User-Agent", "scoop-ai/0.1.0")
                .timeout(Duration.ofSeconds(60))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val finalUri = response.uri().toString()
            buildJsonObject {
                put("original_url", urlNotNull)
                put("final_url", finalUri)
                put("status", response.statusCode())
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to resolve redirect: ${e.message}")
            }.toString()
        }
    }
}
