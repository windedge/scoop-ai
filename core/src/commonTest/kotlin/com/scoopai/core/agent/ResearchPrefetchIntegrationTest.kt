package com.scoopai.core.agent

import com.scoopai.core.tools.api.ArchiveInspector
import com.scoopai.core.tools.api.GitHubApi
import com.scoopai.core.tools.api.WebFetcher
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResearchPrefetchIntegrationTest {

    // ---------------------------------------------------------------
    // Mock implementations
    // ---------------------------------------------------------------

    private fun mockGitHubApi(
        repoInfoJson: String = """{"full_name":"owner/repo","description":"test","homepage":"https://example.com","license":{"spdx_id":"MIT"},"default_branch":"main","stargazers_count":100}""",
        releaseJson: String = """{"tag_name":"v1.0.0","prerelease":false,"assets":[{"name":"app-1.0.zip","browser_download_url":"https://example.com/app-1.0.zip","size":1000}]}""",
        allReleasesJson: String = """[]""",
    ): GitHubApi = object : GitHubApi {
        override suspend fun parseGitHubUrl(url: String?) = """{"owner":"owner","repo":"repo"}"""
        override suspend fun fetchRepoInfo(owner: String?, repo: String?) = repoInfoJson
        override suspend fun fetchLatestRelease(owner: String?, repo: String?) = releaseJson
        override suspend fun fetchAllReleases(owner: String?, repo: String?, perPage: Int) = allReleasesJson
        override suspend fun listReleaseAssets(owner: String?, repo: String?, tag: String?) = """[]"""
        override suspend fun fetchFileContent(owner: String?, repo: String?, path: String?) = """{"path":"LICENSE","content":"MIT"}"""
    }

    private fun mockArchiveInspector(
        searchResultJson: String = """{"matches":[{"file":"src/main.rs","line":1,"snippet":"XDG_CONFIG_HOME","pattern":"XDG"}],"files_scanned":10}"""
    ): ArchiveInspector = object : ArchiveInspector {
        override suspend fun listArchiveContents(url: String?) = """{"url":"","files":[]}"""
        override suspend fun findExecutables(url: String?) = """[]"""
        override suspend fun detectInstallerType(url: String?) = """{"url":"","installer_type":"zip"}"""
        override suspend fun searchTextInArchive(url: String?, patterns: String?, includeGlobs: String?, maxResults: Int, maxFileSizeBytes: Long) = searchResultJson
    }

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

    @Test
    fun `prefetchResearchData returns complete result for GitHub URL`() = runBlocking {
        val api = mockGitHubApi()
        val archive = mockArchiveInspector()

        val result = prefetchResearchData("https://github.com/owner/repo", api, archiveInspector = archive)

        assertTrue(result.isGitHub)
        assertEquals("owner", result.owner)
        assertEquals("repo", result.repo)
        assertNotNull(result.repoInfo)
        assertEquals("MIT", result.repoInfo!!.license)
        assertNotNull(result.latestRelease)
        assertEquals("v1.0.0", result.latestRelease!!.tagName)
        assertEquals("1.0.0", result.version)
    }

    @Test
    fun `prefetchResearchData returns minimal result for non-GitHub URL`() = runBlocking {
        val api = mockGitHubApi()

        val result = prefetchResearchData("https://example.com/some-tool", api)

        assertEquals(false, result.isGitHub)
        assertEquals("https://example.com/some-tool", result.originalUrl)
        assertNull(result.repoInfo)
    }

    @Test
    fun `prefetchResearchData fetches stable release when latest is prerelease`() = runBlocking {
        val api = mockGitHubApi(
            releaseJson = """{"tag_name":"v2.0.0-beta","prerelease":true,"assets":[]}""",
            allReleasesJson = """[{"tag_name":"v2.0.0-beta","prerelease":true},{"tag_name":"v1.9.0","prerelease":false,"assets":[]}]"""
        )

        val result = prefetchResearchData("https://github.com/owner/repo", api)

        assertNotNull(result.stableRelease)
        assertEquals("v1.9.0", result.stableRelease!!.tagName)
    }

    @Test
    fun `prefetchResearchData handles API failure gracefully`() = runBlocking {
        val api = object : GitHubApi {
            override suspend fun parseGitHubUrl(url: String?) = """{"owner":"owner","repo":"repo"}"""
            override suspend fun fetchRepoInfo(owner: String?, repo: String?) = throw RuntimeException("Network error")
            override suspend fun fetchLatestRelease(owner: String?, repo: String?) = throw RuntimeException("Network error")
            override suspend fun fetchAllReleases(owner: String?, repo: String?, perPage: Int) = """[]"""
            override suspend fun listReleaseAssets(owner: String?, repo: String?, tag: String?) = """[]"""
            override suspend fun fetchFileContent(owner: String?, repo: String?, path: String?) = """{}"""
        }

        val result = prefetchResearchData("https://github.com/owner/repo", api)

        assertTrue(result.isGitHub)
        // repoInfo/release are null due to caught exceptions, but call should not throw
        assertNull(result.repoInfo)
        assertNull(result.latestRelease)
    }

    @Test
    fun `prefetchResearchData detects checksums file`() = runBlocking {
        val api = mockGitHubApi(
            releaseJson = """{"tag_name":"v1.0.0","prerelease":false,"assets":[{"name":"app-1.0.zip","browser_download_url":"url1"},{"name":"checksums.txt","browser_download_url":"url2"}]}"""
        )

        val result = prefetchResearchData("https://github.com/owner/repo", api)

        assertEquals("checksums.txt", result.detectedChecksumsFile)
    }

    @Test
    fun `prefetchResearchData cleans homepage`() = runBlocking {
        val api = mockGitHubApi(
            repoInfoJson = """{"full_name":"owner/repo","homepage":"https://discord.gg/myproject","license":{"spdx_id":"MIT"}}"""
        )

        val result = prefetchResearchData("https://github.com/owner/repo", api)

        assertEquals("https://github.com/owner/repo", result.homepageCleaned)
    }

    // ---------------------------------------------------------------
    // digest -> hash propagation
    // ---------------------------------------------------------------

    @Test
    fun `asset digest sha256 propagates to windowsAssets hash`() = runBlocking {
        val hex = "a".repeat(64)
        val api = mockGitHubApi(
            releaseJson = """{"tag_name":"v1.0.0","prerelease":false,"assets":[{"name":"app-1.0.zip","browser_download_url":"https://example.com/app-1.0.zip","digest":"sha256:$hex"}]}"""
        )

        val result = prefetchResearchData("https://github.com/owner/repo", api)

        assertEquals(hex, result.windowsAssets.single().hash)
    }

    @Test
    fun `asset without digest keeps empty windowsAssets hash`() = runBlocking {
        val api = mockGitHubApi(
            releaseJson = """{"tag_name":"v1.0.0","prerelease":false,"assets":[{"name":"app-1.0.zip","browser_download_url":"https://example.com/app-1.0.zip"}]}"""
        )

        val result = prefetchResearchData("https://github.com/owner/repo", api)

        assertEquals("", result.windowsAssets.single().hash)
    }
}
