package com.scoopai.tools.github

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validateNotNull
import com.scoopai.core.tools.api.GitHubApi
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

@LLMDescription("GitHub API tools for gathering project information")
class KtorGitHubApi : GitHubApi, ToolSet {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    // ---------------------------------------------------------------------------
    // HTTP helper
    // ---------------------------------------------------------------------------

    private fun httpGet(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "scoop-ai/0.1.0")
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("GitHub API returned ${response.statusCode()} for $url")
        }
        return response.body()
    }

    // ---------------------------------------------------------------------------
    // JSON helper to safely extract primitive values
    // ---------------------------------------------------------------------------

    private fun JsonObject.getStringOrNull(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private fun JsonObject.getLongOrNull(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.content?.toLongOrNull() }.getOrNull()

    private fun JsonObject.getIntOrNull(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull()

    // ---------------------------------------------------------------------------
    // Tool implementations
    // ---------------------------------------------------------------------------

    @Tool
    @LLMDescription("Parse a GitHub URL to extract the owner and repository name")
    override suspend fun parseGitHubUrl(
        @LLMDescription("A GitHub URL such as https://github.com/owner/repo")
        url: String?
    ): String {
        val urlNotNull = validateNotNull(url) { "Parameter 'url' is required. Provide a GitHub URL such as https://github.com/owner/repo." }
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
    ): String {
        val ownerNotNull = validateNotNull(owner) { "Parameter 'owner' is required. Provide the repository owner." }
        val repoNotNull = validateNotNull(repo) { "Parameter 'repo' is required. Provide the repository name." }
        return try {
            val body = httpGet("https://api.github.com/repos/$ownerNotNull/$repoNotNull")
            val json = Json.parseToJsonElement(body).jsonObject

            val license = json["license"]?.jsonObject
            buildJsonObject {
                put("full_name", json.getStringOrNull("full_name") ?: "$ownerNotNull/$repoNotNull")
                put("description", json.getStringOrNull("description"))
                put("homepage", json.getStringOrNull("homepage"))
                put("license", license?.getStringOrNull("spdx_id"))
                put("default_branch", json.getStringOrNull("default_branch"))
                put("stars", json.getIntOrNull("stargazers_count"))
                put("forks", json.getIntOrNull("forks_count"))
                put("open_issues", json.getIntOrNull("open_issues_count"))
                val topics = json["topics"]?.jsonArray
                if (topics != null) {
                    put("topics", topics)
                }
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to fetch repo info: ${e.message}")
            }.toString()
        }
    }

    @Tool
    @LLMDescription("Fetch the latest release for a GitHub repository including tag name, release notes, and downloadable assets")
    override suspend fun fetchLatestRelease(
        @LLMDescription("The repository owner")
        owner: String?,
        @LLMDescription("The repository name")
        repo: String?
    ): String {
        val ownerNotNull = validateNotNull(owner) { "Parameter 'owner' is required. Provide the repository owner." }
        val repoNotNull = validateNotNull(repo) { "Parameter 'repo' is required. Provide the repository name." }
        return try {
            val body = httpGet("https://api.github.com/repos/$ownerNotNull/$repoNotNull/releases/latest")
            val json = Json.parseToJsonElement(body).jsonObject
            releaseToJson(json).toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to fetch latest release: ${e.message}")
            }.toString()
        }
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
    ): String {
        val ownerNotNull = validateNotNull(owner) { "Parameter 'owner' is required. Provide the repository owner." }
        val repoNotNull = validateNotNull(repo) { "Parameter 'repo' is required. Provide the repository name." }
        return try {
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
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to fetch releases: ${e.message}")
            }.toString()
        }
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
    ): String {
        val ownerNotNull = validateNotNull(owner) { "Parameter 'owner' is required. Provide the repository owner." }
        val repoNotNull = validateNotNull(repo) { "Parameter 'repo' is required. Provide the repository name." }
        val tagNotNull = validateNotNull(tag) { "Parameter 'tag' is required. Provide the release tag (e.g. v1.0.0)." }
        return try {
            val body = httpGet("https://api.github.com/repos/$ownerNotNull/$repoNotNull/releases/tags/$tagNotNull")
            val json = Json.parseToJsonElement(body).jsonObject
            val assets = json["assets"]?.jsonArray ?: buildJsonArray {}
            buildJsonArray {
                for (asset in assets) {
                    add(assetToJson(asset.jsonObject))
                }
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to list release assets: ${e.message}")
            }.toString()
        }
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
    ): String {
        val ownerNotNull = validateNotNull(owner) { "Parameter 'owner' is required. Provide the repository owner." }
        val repoNotNull = validateNotNull(repo) { "Parameter 'repo' is required. Provide the repository name." }
        val pathNotNull = validateNotNull(path) { "Parameter 'path' is required. Provide the file path within the repository." }
        return try {
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
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to fetch file content: ${e.message}")
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
