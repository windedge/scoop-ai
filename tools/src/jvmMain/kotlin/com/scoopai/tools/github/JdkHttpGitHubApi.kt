package com.scoopai.tools.github

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validateNotNull
import com.scoopai.core.tools.api.GitHubApi
import com.scoopai.tools.http.RetryingHttpClient
import com.scoopai.tools.http.RetryingHttpClient.HttpException
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

@LLMDescription("GitHub API tools for gathering project information")
class JdkHttpGitHubApi(
    private val githubToken: String? = null,
    private val httpClient: RetryingHttpClient = RetryingHttpClient(),
) : GitHubApi, ToolSet {

    // ---------------------------------------------------------------------------
    // HTTP helper
    // ---------------------------------------------------------------------------

    private fun httpGet(url: String): String {
        val baseHeaders = mutableMapOf(
            "User-Agent" to "scoop-ai/0.1.0",
            "Accept" to "application/vnd.github+json",
        )
        if (!githubToken.isNullOrBlank()) {
            baseHeaders["Authorization"] = "Bearer $githubToken"
        }
        val response = httpClient.get(url, baseHeaders)
        return response.body()
    }

    // ---------------------------------------------------------------------------
    // Structured error helpers
    // ---------------------------------------------------------------------------

    /** Build a structured error JSON from an exception. Detects rate-limit 403s. */
    private fun structuredError(operation: String, e: Exception): String {
        val isRateLimit = e is HttpException && e.isRateLimited
        val statusCode = (e as? HttpException)?.statusCode
        return buildJsonObject {
            put("error", "Failed to $operation: ${e.message}")
            if (isRateLimit || statusCode == 403) {
                put("error_type", "rate_limited")
                put("status", statusCode ?: 403)
                put("retryable", false)
                put("action", "STOP all GitHub API calls. Use searchTextInArchive on the source code zip instead. Do NOT retry any GitHub API request.")
            }
        }.toString()
    }

    // ---------------------------------------------------------------------------
    // Parameter validation & API call wrapper
    // ---------------------------------------------------------------------------

    private fun <T : Any> requireParam(value: T?, name: String, hint: String = ""): T {
        val desc = if (hint.isNotBlank()) hint else "the $name value"
        return validateNotNull(value) { "Parameter '$name' is required. Provide $desc." }
    }

    private inline fun apiCall(operation: String, block: () -> String): String {
        return try {
            block()
        } catch (e: Exception) {
            structuredError(operation, e)
        }
    }

    // ---------------------------------------------------------------------------
    // JSON helper to safely extract primitive values
    // ---------------------------------------------------------------------------

    private fun JsonObject.getStringOrNull(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return runCatching { element.jsonPrimitive.content }.getOrNull()
    }

    private fun JsonObject.getLongOrNull(key: String): Long? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return runCatching { element.jsonPrimitive.content.toLongOrNull() }.getOrNull()
    }

    private fun JsonObject.getIntOrNull(key: String): Int? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return runCatching { element.jsonPrimitive.content.toIntOrNull() }.getOrNull()
    }

    // ---------------------------------------------------------------------------
    // Tool implementations
    // ---------------------------------------------------------------------------

    @Tool
    @LLMDescription("Parse a GitHub URL to extract the owner and repository name")
    override suspend fun parseGitHubUrl(
        @LLMDescription("A GitHub URL such as https://github.com/owner/repo")
        url: String?
    ): String {
        val urlNotNull = requireParam(url, "url", "a GitHub URL such as https://github.com/owner/repo")
        val regex = Regex("""(?:https?://)?github\.com/([^/]+)/([^/]+?)(?:/|$|\?|#)""")
        val match = regex.find(urlNotNull)
        if (match != null) {
            val owner = match.groupValues[1]
            val repo = match.groupValues[2].removeSuffix(".git")
            return buildJsonObject {
                put("owner", owner)
                put("repo", repo)
            }.toString()
        }
        return buildJsonObject {
            put("error", "Not a valid GitHub URL")
        }.toString()
    }

    @Tool
    @LLMDescription("Fetch repository information from GitHub, including description, license, topics, etc.")
    override suspend fun fetchRepoInfo(
        @LLMDescription("The repository owner (user or organization)")
        owner: String?,
        @LLMDescription("The repository name")
        repo: String?
    ): String = apiCall("fetch repo info") {
        val ownerNotNull = requireParam(owner, "owner", "the repository owner")
        val repoNotNull = requireParam(repo, "repo", "the repository name")
        val body = httpGet("https://api.github.com/repos/$ownerNotNull/$repoNotNull")
        val json = Json.parseToJsonElement(body).jsonObject

        val license = json["license"]?.takeIf { it !is JsonNull }?.jsonObject
        buildJsonObject {
            put("full_name", json.getStringOrNull("full_name") ?: "$ownerNotNull/$repoNotNull")
            put("description", json.getStringOrNull("description"))
            put("homepage", json.getStringOrNull("homepage"))
            put("license", license?.getStringOrNull("spdx_id") ?: "NOASSERTION")
            put("default_branch", json.getStringOrNull("default_branch"))
            put("stars", json.getIntOrNull("stargazers_count"))
            put("forks", json.getIntOrNull("forks_count"))
            put("open_issues", json.getIntOrNull("open_issues_count"))
            val topics = json["topics"]?.jsonArray
            if (topics != null) {
                put("topics", topics)
            }
        }.toString()
    }

    @Tool
    @LLMDescription("Fetch the latest release for a GitHub repository including tag name, release notes, and downloadable assets")
    override suspend fun fetchLatestRelease(
        @LLMDescription("The repository owner")
        owner: String?,
        @LLMDescription("The repository name")
        repo: String?
    ): String = apiCall("fetch latest release") {
        val ownerNotNull = requireParam(owner, "owner", "the repository owner")
        val repoNotNull = requireParam(repo, "repo", "the repository name")
        val body = httpGet("https://api.github.com/repos/$ownerNotNull/$repoNotNull/releases/latest")
        val json = Json.parseToJsonElement(body).jsonObject
        releaseToJson(json).toString()
    }

    @Tool
    @LLMDescription("Fetch multiple releases for a GitHub repository")
    override suspend fun fetchAllReleases(
        @LLMDescription("The repository owner")
        owner: String?,
        @LLMDescription("The repository name")
        repo: String?,
        @LLMDescription("Number of releases to return (default 5)")
        perPage: Int
    ): String = apiCall("fetch releases") {
        val ownerNotNull = requireParam(owner, "owner", "the repository owner")
        val repoNotNull = requireParam(repo, "repo", "the repository name")
        val effectivePerPage = if (perPage <= 0) 5 else perPage
        val body = httpGet("https://api.github.com/repos/$ownerNotNull/$repoNotNull/releases?per_page=$effectivePerPage")
        val array = Json.parseToJsonElement(body).jsonArray
        buildJsonArray {
            for (element in array) {
                val release = element.jsonObject
                add(buildJsonObject {
                    put("tag_name", release.getStringOrNull("tag_name"))
                    put("name", release.getStringOrNull("name"))
                    put("prerelease", release["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false)
                    val assets = release["assets"]?.jsonArray
                    if (assets != null) {
                        put("assets", buildJsonArray {
                            for (asset in assets) {
                                add(assetToJson(asset.jsonObject))
                            }
                        })
                    }
                })
            }
        }.toString()
    }

    @Tool
    @LLMDescription("List assets for a specific release tag in a GitHub repository")
    override suspend fun listReleaseAssets(
        @LLMDescription("The repository owner")
        owner: String?,
        @LLMDescription("The repository name")
        repo: String?,
        @LLMDescription("The release tag (e.g. v1.0.0)")
        tag: String?
    ): String = apiCall("list release assets") {
        val ownerNotNull = requireParam(owner, "owner", "the repository owner")
        val repoNotNull = requireParam(repo, "repo", "the repository name")
        val tagNotNull = requireParam(tag, "tag", "the release tag (e.g. v1.0.0)")
        val body = httpGet("https://api.github.com/repos/$ownerNotNull/$repoNotNull/releases/tags/$tagNotNull")
        val json = Json.parseToJsonElement(body).jsonObject
        val assets = json["assets"]?.jsonArray ?: buildJsonArray {}
        buildJsonArray {
            for (asset in assets) {
                add(assetToJson(asset.jsonObject))
            }
        }.toString()
    }

    @Tool
    @LLMDescription("Fetch the content of a file from a GitHub repository (decoded from base64)")
    override suspend fun fetchFileContent(
        @LLMDescription("The repository owner")
        owner: String?,
        @LLMDescription("The repository name")
        repo: String?,
        @LLMDescription("The file path within the repository (e.g. README.md)")
        path: String?
    ): String = apiCall("fetch file content") {
        val ownerNotNull = requireParam(owner, "owner", "the repository owner")
        val repoNotNull = requireParam(repo, "repo", "the repository name")
        val pathNotNull = requireParam(path, "path", "the file path within the repository")
        val encodedPath = pathNotNull.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }
        val body = httpGet("https://api.github.com/repos/$ownerNotNull/$repoNotNull/contents/$encodedPath")
        val json = Json.parseToJsonElement(body).jsonObject

        val encoding = json.getStringOrNull("encoding")
        val contentB64 = json.getStringOrNull("content")

        if (encoding == "base64" && contentB64 != null) {
            val cleaned = contentB64.replace("\\s".toRegex(), "")
            val decoded = Base64.getDecoder().decode(cleaned).decodeToString()
            buildJsonObject {
                put("path", pathNotNull)
                put("content", decoded)
            }.toString()
        } else {
            buildJsonObject {
                put("path", pathNotNull)
                put("content", json.getStringOrNull("content") ?: "")
                put("encoding", json.getStringOrNull("encoding") ?: "unknown")
            }.toString()
        }
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private fun releaseToJson(release: JsonObject): JsonObject = buildJsonObject {
        put("tag_name", release.getStringOrNull("tag_name"))
        put("name", release.getStringOrNull("name"))
        val rawBody = release.getStringOrNull("body") ?: ""
        put("body", if (rawBody.length > 500) rawBody.substring(0, 500) + "..." else rawBody)
        put("prerelease", release["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false)
        val assets = release["assets"]?.jsonArray
        if (assets != null) {
            put("assets", buildJsonArray {
                for (asset in assets) {
                    add(assetToJson(asset.jsonObject))
                }
            })
        }
    }

    private fun assetToJson(asset: JsonObject): JsonObject = buildJsonObject {
        put("name", asset.getStringOrNull("name"))
        put("browser_download_url", asset.getStringOrNull("browser_download_url"))
        put("size", asset.getLongOrNull("size"))
    }
}