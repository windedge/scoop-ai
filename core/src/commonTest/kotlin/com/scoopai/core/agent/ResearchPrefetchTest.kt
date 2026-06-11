package com.scoopai.core.agent

import com.scoopai.core.tools.api.ArchiveInspector
import com.scoopai.core.tools.api.GitHubApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ResearchPrefetchTest {

    // ---------------------------------------------------------------
    // cleanHomepage
    // ---------------------------------------------------------------

    @Test
    fun `cleanHomepage removes discord link`() {
        val result = cleanHomepage("https://discord.gg/myproject", "owner", "repo")
        assertEquals("https://github.com/owner/repo", result)
    }

    @Test
    fun `cleanHomepage removes slack link`() {
        val result = cleanHomepage("https://slack.com/myproject", "owner", "repo")
        assertEquals("https://github.com/owner/repo", result)
    }

    @Test
    fun `cleanHomepage removes twitter link`() {
        val result = cleanHomepage("https://twitter.com/myproject", "owner", "repo")
        assertEquals("https://github.com/owner/repo", result)
    }

    @Test
    fun `cleanHomepage keeps official website`() {
        val result = cleanHomepage("https://www.kooha.com", "owner", "repo")
        assertEquals("https://www.kooha.com", result)
    }

    @Test
    fun `cleanHomepage falls back to github URL when null`() {
        val result = cleanHomepage(null, "owner", "repo")
        assertEquals("https://github.com/owner/repo", result)
    }

    @Test
    fun `cleanHomepage keeps github repo URL`() {
        val result = cleanHomepage("https://github.com/owner/repo", "owner", "repo")
        assertEquals("https://github.com/owner/repo", result)
    }

    // ---------------------------------------------------------------
    // detectChecksumsFile
    // ---------------------------------------------------------------

    @Test
    fun `detectChecksumsFile finds checksums txt`() {
        val assets = listOf(AssetSnapshot("app-1.0.zip", "url1"), AssetSnapshot("checksums.txt", "url2"))
        assertEquals("checksums.txt", detectChecksumsFile(assets))
    }

    @Test
    fun `detectChecksumsFile finds SHA256SUMS`() {
        val assets = listOf(AssetSnapshot("app-1.0.zip", "url1"), AssetSnapshot("SHA256SUMS", "url2"))
        assertEquals("SHA256SUMS", detectChecksumsFile(assets))
    }

    @Test
    fun `detectChecksumsFile finds sha256 suffix`() {
        val assets = listOf(AssetSnapshot("app-1.0.zip", "url1"), AssetSnapshot("app-1.0.zip.sha256", "url2"))
        assertEquals("app-1.0.zip.sha256", detectChecksumsFile(assets))
    }

    @Test
    fun `detectChecksumsFile finds MD5SUMS`() {
        val assets = listOf(AssetSnapshot("app-1.0.zip", "url1"), AssetSnapshot("MD5SUMS", "url2"))
        assertEquals("MD5SUMS", detectChecksumsFile(assets))
    }

    @Test
    fun `detectChecksumsFile returns empty when no checksums`() {
        val assets = listOf(AssetSnapshot("app-1.0.zip", "url1"), AssetSnapshot("app-1.0.tar.gz", "url2"))
        assertEquals("", detectChecksumsFile(assets))
    }

    @Test
    fun `detectChecksumsFile handles empty list`() {
        assertEquals("", detectChecksumsFile(emptyList()))
    }

    // ---------------------------------------------------------------
    // extractVersion
    // ---------------------------------------------------------------

    @Test
    fun `extractVersion strips v prefix`() {
        assertEquals("1.0.0", extractVersion("v1.0.0"))
    }

    @Test
    fun `extractVersion handles version without v prefix`() {
        assertEquals("1.0.0", extractVersion("1.0.0"))
    }

    @Test
    fun `extractVersion returns empty for null`() {
        assertEquals("", extractVersion(null))
    }

    @Test
    fun `extractVersion handles complex tag`() {
        assertEquals("2.1.0-rc.1", extractVersion("v2.1.0-rc.1"))
    }

    // ---------------------------------------------------------------
    // selectStableRelease
    // ---------------------------------------------------------------

    @Test
    fun `selectStableRelease picks first non-prerelease`() {
        val releases = listOf(
            ReleaseSnapshot("v2.0.0-beta", prerelease = true),
            ReleaseSnapshot("v1.9.0", prerelease = false),
            ReleaseSnapshot("v1.8.0", prerelease = false),
        )
        val result = selectStableRelease(releases)
        assertTrue(result != null)
        assertEquals("v1.9.0", result!!.tagName)
    }

    @Test
    fun `selectStableRelease returns null when all prerelease`() {
        val releases = listOf(
            ReleaseSnapshot("v2.0.0-beta", prerelease = true),
            ReleaseSnapshot("v2.0.0-alpha", prerelease = true),
        )
        assertNull(selectStableRelease(releases))
    }

    @Test
    fun `selectStableRelease returns null for empty list`() {
        assertNull(selectStableRelease(emptyList()))
    }

    // ---------------------------------------------------------------
    // executePrefetch
    // ---------------------------------------------------------------

    @Test
    fun `executePrefetch returns empty result when gitHubApi is null`() = runBlocking {
        val result = executePrefetch("https://github.com/owner/repo", null)
        assertEquals(false, result.isGitHub)
        assertEquals("https://github.com/owner/repo", result.originalUrl)
    }

    @Test
    fun `executePrefetch returns empty result for non-GitHub URL`() = runBlocking {
        val api: GitHubApi = object : GitHubApi {
            override suspend fun parseGitHubUrl(url: String?) = """{}"""
            override suspend fun fetchRepoInfo(owner: String?, repo: String?) = """{}"""
            override suspend fun fetchLatestRelease(owner: String?, repo: String?) = """{}"""
            override suspend fun fetchAllReleases(owner: String?, repo: String?, perPage: Int) = """[]"""
            override suspend fun listReleaseAssets(owner: String?, repo: String?, tag: String?) = """[]"""
            override suspend fun fetchFileContent(owner: String?, repo: String?, path: String?) = """{}"""
        }
        val result = executePrefetch("https://example.com/tool", api)
        assertEquals(false, result.isGitHub)
        assertEquals("https://example.com/tool", result.originalUrl)
    }

    @Test
    fun `executePrefetch returns full result for GitHub URL`() = runBlocking {
        val api: GitHubApi = object : GitHubApi {
            override suspend fun parseGitHubUrl(url: String?) = """{"owner":"owner","repo":"repo"}"""
            override suspend fun fetchRepoInfo(owner: String?, repo: String?) =
                """{"full_name":"owner/repo","description":"test","homepage":"https://example.com","license":{"spdx_id":"MIT"},"default_branch":"main","stargazers_count":100}"""
            override suspend fun fetchLatestRelease(owner: String?, repo: String?) =
                """{"tag_name":"v1.0.0","prerelease":false,"assets":[{"name":"app-1.0.zip","browser_download_url":"https://example.com/app-1.0.zip","size":1000}]}"""
            override suspend fun fetchAllReleases(owner: String?, repo: String?, perPage: Int) = """[]"""
            override suspend fun listReleaseAssets(owner: String?, repo: String?, tag: String?) = """[]"""
            override suspend fun fetchFileContent(owner: String?, repo: String?, path: String?) = """{}"""
        }
        val result = executePrefetch("https://github.com/owner/repo", api)
        assertTrue(result.isGitHub)
        assertEquals("owner", result.owner)
        assertEquals("1.0.0", result.version)
    }

    @Test
    fun `executePrefetch handles API failure gracefully`() = runBlocking {
        val api: GitHubApi = object : GitHubApi {
            override suspend fun parseGitHubUrl(url: String?) = """{}"""
            override suspend fun fetchRepoInfo(owner: String?, repo: String?) = throw RuntimeException("Network error")
            override suspend fun fetchLatestRelease(owner: String?, repo: String?) = throw RuntimeException("Network error")
            override suspend fun fetchAllReleases(owner: String?, repo: String?, perPage: Int) = throw RuntimeException("Network error")
            override suspend fun listReleaseAssets(owner: String?, repo: String?, tag: String?) = """[]"""
            override suspend fun fetchFileContent(owner: String?, repo: String?, path: String?) = """{}"""
        }
        val result = executePrefetch("https://github.com/owner/repo", api)
        // Should not throw; prefetchResearchData internally catches API errors
        // and returns isGitHub=true (GitHub URL parsed), but repoInfo/release are null
        assertNotNull(result)
        assertTrue(result.isGitHub)
        assertNull(result.repoInfo)
        assertNull(result.latestRelease)
    }
}
