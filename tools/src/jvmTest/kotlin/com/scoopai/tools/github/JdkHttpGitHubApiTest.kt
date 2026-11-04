package com.scoopai.tools.github

import com.scoopai.tools.http.RetryingHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.net.http.HttpResponse
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdkHttpGitHubApiTest {

    // ---------------------------------------------------------------------------
    // Stub HttpResponse
    // ---------------------------------------------------------------------------
    private data class StubHttpResponse(
        private val statusCode: Int = 200,
        private val body: String = """{}""",
    ) : HttpResponse<String> {
        override fun statusCode(): Int = statusCode
        override fun body(): String = body
        override fun uri() = java.net.URI.create("http://example.com")
        override fun headers() = java.net.http.HttpHeaders.of(mapOf()) { _, _ -> true }
        override fun previousResponse() = null
        override fun request() = null
        override fun sslSession() = null
        override fun version() = java.net.http.HttpClient.Version.HTTP_1_1
    }

    // ---------------------------------------------------------------------------
    // Spy HttpClient that records headers
    // ---------------------------------------------------------------------------
    private class HeaderCapturingHttpClient : RetryingHttpClient() {
        var capturedHeaders: Map<String, String> = emptyMap()
            private set
        var capturedUrl: String = ""
            private set

        override fun get(url: String, headers: Map<String, String>): HttpResponse<String> {
            capturedUrl = url
            capturedHeaders = headers
            return StubHttpResponse(body = """{}""")
        }
    }

    // ---------------------------------------------------------------
    // `with token adds Authorization header`
    // ---------------------------------------------------------------
    @Test
    fun `with token adds Authorization header`() = runBlocking {
        val httpSpy = HeaderCapturingHttpClient()
        val api = JdkHttpGitHubApi(githubToken = "ghp_xxxx", httpClient = httpSpy)

        api.fetchRepoInfo(owner = "test-owner", repo = "test-repo")

        assertEquals("https://api.github.com/repos/test-owner/test-repo", httpSpy.capturedUrl)
        assertEquals("Bearer ghp_xxxx", httpSpy.capturedHeaders["Authorization"])
    }

    // ---------------------------------------------------------------
    // `without token does not add Authorization header`
    // ---------------------------------------------------------------
    @Test
    fun `without token does not add Authorization header`() = runBlocking {
        val httpSpy = HeaderCapturingHttpClient()
        val api = JdkHttpGitHubApi(httpClient = httpSpy)

        api.fetchRepoInfo(owner = "test-owner", repo = "test-repo")

        assertNull(httpSpy.capturedHeaders["Authorization"])
        assertTrue(httpSpy.capturedHeaders.containsKey("User-Agent"))
        assertTrue(httpSpy.capturedHeaders.containsKey("Accept"))
    }

    // ---------------------------------------------------------------
    // `with empty token behaves as without token`
    // ---------------------------------------------------------------
    @Test
    fun `with empty token behaves as without token`() = runBlocking {
        val httpSpy = HeaderCapturingHttpClient()
        val api = JdkHttpGitHubApi(githubToken = "", httpClient = httpSpy)

        api.fetchRepoInfo(owner = "test-owner", repo = "test-repo")

        assertNull(httpSpy.capturedHeaders["Authorization"])
    }

    // ---------------------------------------------------------------
    // `with blank token behaves as without token`
    // ---------------------------------------------------------------
    @Test
    fun `with blank token behaves as without token`() = runBlocking {
        val httpSpy = HeaderCapturingHttpClient()
        val api = JdkHttpGitHubApi(githubToken = "  ", httpClient = httpSpy)

        api.fetchRepoInfo(owner = "test-owner", repo = "test-repo")

        assertNull(httpSpy.capturedHeaders["Authorization"])
    }

    // ---------------------------------------------------------------
    // `token appears in all GitHub API calls`
    // ---------------------------------------------------------------
    @Test
    fun `token appears in fetchLatestRelease call`() = runBlocking {
        val httpSpy = HeaderCapturingHttpClient()
        val api = JdkHttpGitHubApi(githubToken = "ghp_yyyy", httpClient = httpSpy)

        api.fetchLatestRelease(owner = "o", repo = "r")

        assertEquals("Bearer ghp_yyyy", httpSpy.capturedHeaders["Authorization"])
    }

    @Test
    fun `token appears in fetchFileContent call`() = runBlocking {
        val httpSpy = HeaderCapturingHttpClient()
        val api = JdkHttpGitHubApi(githubToken = "ghp_zzzz", httpClient = httpSpy)

        api.fetchFileContent(owner = "o", repo = "r", path = "README.md")

        assertEquals("Bearer ghp_zzzz", httpSpy.capturedHeaders["Authorization"])
    }

    // ---------------------------------------------------------------
    // `fetchRepoInfo returns NOASSERTION when license is null`
    // ---------------------------------------------------------------
    @Test
    fun `fetchRepoInfo returns NOASSERTION when license is null`() = runBlocking {
        val httpSpy = object : RetryingHttpClient() {
            override fun get(url: String, headers: Map<String, String>): HttpResponse<String> {
                return StubHttpResponse(body = """{"license": null, "full_name": "o/r", "description": "test"}""")
            }
        }
        val api = JdkHttpGitHubApi(httpClient = httpSpy)

        val result = api.fetchRepoInfo(owner = "o", repo = "r")
        val json = Json.parseToJsonElement(result).jsonObject

        assertEquals("NOASSERTION", json["license"]?.jsonPrimitive?.content)
    }

    // ---------------------------------------------------------------
    // `fetchRepoInfo returns NOASSERTION when spdx_id is null`
    // ---------------------------------------------------------------
    @Test
    fun `fetchRepoInfo returns NOASSERTION when spdx_id is null`() = runBlocking {
        val httpSpy = object : RetryingHttpClient() {
            override fun get(url: String, headers: Map<String, String>): HttpResponse<String> {
                return StubHttpResponse(body = """{"license": {"spdx_id": null}, "full_name": "o/r", "description": "test"}""")
            }
        }
        val api = JdkHttpGitHubApi(httpClient = httpSpy)

        val result = api.fetchRepoInfo(owner = "o", repo = "r")
        val json = Json.parseToJsonElement(result).jsonObject

        assertEquals("NOASSERTION", json["license"]?.jsonPrimitive?.content)
    }

    // ---------------------------------------------------------------
    // `fetchRepoInfo returns spdx_id when license is present`
    // ---------------------------------------------------------------
    @Test
    fun `fetchRepoInfo returns spdx_id when license is present`() = runBlocking {
        val httpSpy = object : RetryingHttpClient() {
            override fun get(url: String, headers: Map<String, String>): HttpResponse<String> {
                return StubHttpResponse(body = """{"license": {"spdx_id": "MIT"}, "full_name": "o/r", "description": "test"}""")
            }
        }
        val api = JdkHttpGitHubApi(httpClient = httpSpy)

        val result = api.fetchRepoInfo(owner = "o", repo = "r")
        val json = Json.parseToJsonElement(result).jsonObject

        assertEquals("MIT", json["license"]?.jsonPrimitive?.content)
    }

    // ---------------------------------------------------------------
    // `assetToJson exposes GitHub digest as hash`
    // ---------------------------------------------------------------
    @Test
    fun `assetToJson exposes sha256 digest as hex hash`() = runBlocking {
        val httpSpy = object : RetryingHttpClient() {
            override fun get(url: String, headers: Map<String, String>): HttpResponse<String> {
                val digest = "sha256:" + "a".repeat(64)
                return StubHttpResponse(
                    body = """{"tag_name":"v1.0.0","assets":[{"name":"app.zip","browser_download_url":"https://x/app.zip","size":10,"digest":"$digest"}]}""",
                )
            }
        }
        val api = JdkHttpGitHubApi(httpClient = httpSpy)

        val result = api.fetchLatestRelease(owner = "o", repo = "r")
        val asset = Json.parseToJsonElement(result).jsonObject["assets"]!!.jsonArray[0].jsonObject

        assertEquals("a".repeat(64), asset["hash"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assetToJson strips uppercase SHA256 prefix and keeps valid hex`() = runBlocking {
        // uppercase prefix previously leaked through removePrefix("sha256:")
        val httpSpy = object : RetryingHttpClient() {
            override fun get(url: String, headers: Map<String, String>): HttpResponse<String> {
                val digest = "SHA256:" + "AB".repeat(32)
                return StubHttpResponse(
                    body = """{"tag_name":"v1.0.0","assets":[{"name":"app.zip","browser_download_url":"https://x/app.zip","size":10,"digest":"$digest"}]}""",
                )
            }
        }
        val api = JdkHttpGitHubApi(httpClient = httpSpy)

        val result = api.fetchLatestRelease(owner = "o", repo = "r")
        val asset = Json.parseToJsonElement(result).jsonObject["assets"]!!.jsonArray[0].jsonObject

        assertEquals("AB".repeat(32), asset["hash"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assetToJson omits hash for malformed sha256 payload`() = runBlocking {
        // short hex (not 64 chars) must not surface as a hash
        val httpSpy = object : RetryingHttpClient() {
            override fun get(url: String, headers: Map<String, String>): HttpResponse<String> {
                val digest = "sha256:abc"
                return StubHttpResponse(
                    body = """{"tag_name":"v1.0.0","assets":[{"name":"app.zip","browser_download_url":"https://x/app.zip","size":10,"digest":"$digest"}]}""",
                )
            }
        }
        val api = JdkHttpGitHubApi(httpClient = httpSpy)

        val result = api.fetchLatestRelease(owner = "o", repo = "r")
        val asset = Json.parseToJsonElement(result).jsonObject["assets"]!!.jsonArray[0].jsonObject

        assertTrue(!asset.containsKey("hash"), "hash must be absent for malformed sha256 payload")
    }

    @Test
    fun `assetToJson omits hash when digest is missing`() = runBlocking {
        val httpSpy = object : RetryingHttpClient() {
            override fun get(url: String, headers: Map<String, String>): HttpResponse<String> {
                return StubHttpResponse(
                    body = """{"tag_name":"v1.0.0","assets":[{"name":"app.zip","browser_download_url":"https://x/app.zip","size":10}]}""",
                )
            }
        }
        val api = JdkHttpGitHubApi(httpClient = httpSpy)

        val result = api.fetchLatestRelease(owner = "o", repo = "r")
        val asset = Json.parseToJsonElement(result).jsonObject["assets"]!!.jsonArray[0].jsonObject

        assertTrue(!asset.containsKey("hash"), "hash must be absent when digest is missing")
    }

    @Test
    fun `assetToJson omits hash for non-sha256 digest prefixes`() = runBlocking {
        val httpSpy = object : RetryingHttpClient() {
            override fun get(url: String, headers: Map<String, String>): HttpResponse<String> {
                val digest = "sha512:" + "b".repeat(128)
                return StubHttpResponse(
                    body = """{"tag_name":"v1.0.0","assets":[{"name":"app.zip","browser_download_url":"https://x/app.zip","digest":"$digest"}]}""",
                )
            }
        }
        val api = JdkHttpGitHubApi(httpClient = httpSpy)

        val result = api.fetchLatestRelease(owner = "o", repo = "r")
        val asset = Json.parseToJsonElement(result).jsonObject["assets"]!!.jsonArray[0].jsonObject

        assertTrue(!asset.containsKey("hash"), "hash must be absent for non-sha256 digest")
    }

    // ---------------------------------------------------------------
    // `structuredError still detects rate-limit 403s`
    // ---------------------------------------------------------------
    @Test
    fun `structuredError includes rate_limited type on 403 with rate-limit body`() = runBlocking {
        val httpSpy = object : RetryingHttpClient() {
            override fun get(url: String, headers: Map<String, String>): HttpResponse<String> {
                throw RetryingHttpClient.HttpException(
                    statusCode = 403,
                    url = url,
                    body = """{"message":"API rate limit exceeded"}""",
                )
            }
        }
        val api = JdkHttpGitHubApi(httpClient = httpSpy)

        val result = api.fetchRepoInfo(owner = "o", repo = "r")
        val json = Json.parseToJsonElement(result).jsonObject

        assertEquals("rate_limited", json["error_type"]?.jsonPrimitive?.content)
        assertContains(json["action"]?.jsonPrimitive?.content ?: "", "STOP all GitHub API calls")
    }
}
