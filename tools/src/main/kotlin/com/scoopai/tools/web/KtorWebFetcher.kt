package com.scoopai.tools.web

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validateNotNull
import com.scoopai.core.tools.api.WebFetcher
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@LLMDescription("Web fetching tools for reading web pages and APIs")
class KtorWebFetcher : WebFetcher, ToolSet {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    // ---------------------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------------------

    private fun httpGetString(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "scoop-ai/0.1.0")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("HTTP ${response.statusCode()} for $url")
        }
        return response.body()
    }

    // ---------------------------------------------------------------------------
    // Tool implementations
    // ---------------------------------------------------------------------------

    @Tool
    @LLMDescription("Fetch a web page and return its text content (HTML stripped)")
    override suspend fun fetchWebPage(
        @LLMDescription("The URL of the web page to fetch")
        url: String?
    ): String {
        val urlNotNull = validateNotNull(url) { "Parameter 'url' is required. Provide the URL of the web page to fetch." }
        return try {
            val body = httpGetString(urlNotNull)
            // Strip HTML tags: replace <...> with a space, collapse whitespace
            val text = body.replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
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
        return try {
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            val request = HttpRequest.newBuilder()
                .uri(URI.create(urlNotNull))
                .header("User-Agent", "scoop-ai/0.1.0")
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
