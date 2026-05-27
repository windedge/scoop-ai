package com.scoopai.tools.github

import com.scoopai.tools.http.RetryingHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
