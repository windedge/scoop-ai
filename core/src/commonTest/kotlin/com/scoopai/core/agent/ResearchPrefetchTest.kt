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

    // ---------------------------------------------------------------
    // ALL_CONFIG_PATTERNS contains required patterns
    // ---------------------------------------------------------------

    @Test
    fun `ALL_CONFIG_PATTERNS contains SettingStore`() {
        assertTrue(ALL_CONFIG_PATTERNS.contains("SettingStore"), "ALL_CONFIG_PATTERNS should include SettingStore")
    }

    @Test
    fun `ALL_CONFIG_PATTERNS contains GetConfigPath`() {
        assertTrue(ALL_CONFIG_PATTERNS.contains("GetConfigPath"), "ALL_CONFIG_PATTERNS should include GetConfigPath")
    }

    @Test
    fun `ALL_CONFIG_PATTERNS contains ProgramData`() {
        assertTrue(ALL_CONFIG_PATTERNS.contains("ProgramData"), "ALL_CONFIG_PATTERNS should include ProgramData")
    }

    // ---------------------------------------------------------------
    // parseOwnerRepo
    // ---------------------------------------------------------------

    @Test
    fun `parses standard github URL`() {
        val result = parseOwnerRepo("https://github.com/openai/codex")
        assertNotNull(result)
        assertEquals("openai", result!!.first)
        assertEquals("codex", result.second)
    }

    @Test
    fun `parses api github com URL`() {
        val result = parseOwnerRepo("https://api.github.com/repos/openai/codex/releases")
        assertNotNull(result)
        assertEquals("openai", result!!.first)
        assertEquals("codex", result.second)
    }

    @Test
    fun `parses api github com releases latest URL`() {
        val result = parseOwnerRepo("https://api.github.com/repos/openai/codex/releases/latest")
        assertNotNull(result)
        assertEquals("openai", result!!.first)
        assertEquals("codex", result.second)
    }

    @Test
    fun `parses github URL with trailing releases path`() {
        val result = parseOwnerRepo("https://github.com/openai/codex/releases/latest")
        assertNotNull(result)
        assertEquals("openai", result!!.first)
        assertEquals("codex", result.second)
    }

    @Test
    fun `parses github URL with trailing tags path`() {
        val result = parseOwnerRepo("https://github.com/openai/codex/tags")
        assertNotNull(result)
        assertEquals("openai", result!!.first)
        assertEquals("codex", result.second)
    }

    @Test
    fun `parses github URL with query string`() {
        val result = parseOwnerRepo("https://github.com/openai/codex?tab=readme")
        assertNotNull(result)
        assertEquals("openai", result!!.first)
        assertEquals("codex", result.second)
    }

    @Test
    fun `parses github URL with fragment`() {
        val result = parseOwnerRepo("https://github.com/openai/codex#readme")
        assertNotNull(result)
        assertEquals("openai", result!!.first)
        assertEquals("codex", result.second)
    }

    @Test
    fun `parses github URL without protocol`() {
        val result = parseOwnerRepo("github.com/openai/codex")
        assertNotNull(result)
        assertEquals("openai", result!!.first)
        assertEquals("codex", result.second)
    }

    @Test
    fun `returns null for non-github URLs`() {
        assertNull(parseOwnerRepo("https://example.com/tool"))
    }

    @Test
    fun `returns null for invalid URLs`() {
        assertNull(parseOwnerRepo("not a url"))
    }

    @Test
    fun `handles git suffix in repo name`() {
        val result = parseOwnerRepo("https://github.com/openai/codex.git")
        assertNotNull(result)
        assertEquals("openai", result!!.first)
        assertEquals("codex", result.second)
    }

    @Test
    fun `parses api github com URL without trailing path`() {
        val result = parseOwnerRepo("https://api.github.com/repos/cli/cli")
        assertNotNull(result)
        assertEquals("cli", result!!.first)
        assertEquals("cli", result.second)
    }

    // ---------------------------------------------------------------
    // isPrereleaseVersion
    // ---------------------------------------------------------------

    @Test
    fun `isPrereleaseVersion detects alpha`() {
        assertTrue(isPrereleaseVersion("0.140.0-alpha.19"))
    }

    @Test
    fun `isPrereleaseVersion detects rc`() {
        assertTrue(isPrereleaseVersion("1.0.0-rc1"))
        assertTrue(isPrereleaseVersion("2.0-rc.3"))
    }

    @Test
    fun `isPrereleaseVersion detects beta`() {
        assertTrue(isPrereleaseVersion("1.0.0-beta"))
    }

    @Test
    fun `isPrereleaseVersion detects pre`() {
        assertTrue(isPrereleaseVersion("3.0.0-pre"))
    }

    @Test
    fun `isPrereleaseVersion detects preview`() {
        assertTrue(isPrereleaseVersion("1.0-preview"))
    }

    @Test
    fun `isPrereleaseVersion detects dev`() {
        assertTrue(isPrereleaseVersion("0.9-dev"))
    }

    @Test
    fun `isPrereleaseVersion detects snapshot`() {
        assertTrue(isPrereleaseVersion("1.0-SNAPSHOT"))
    }

    @Test
    fun `isPrereleaseVersion returns false for stable`() {
        assertFalse(isPrereleaseVersion("0.139.0"))
        assertFalse(isPrereleaseVersion("1.2.3"))
        assertFalse(isPrereleaseVersion("2.1.0"))
    }

    @Test
    fun `isPrereleaseVersion handles empty and edge cases`() {
        assertFalse(isPrereleaseVersion(""))
        assertFalse(isPrereleaseVersion("1"))
        assertFalse(isPrereleaseVersion("1.0"))
    }

    @Test
    fun `isPrereleaseVersion does not match non version segments`() {
        assertFalse(isPrereleaseVersion("1.0.0-windows"))
        assertTrue(isPrereleaseVersion("1.0.0-rc-windows"))
    }

    // ---------------------------------------------------------------
    // detectReleasesFile
    // ---------------------------------------------------------------

    @Test
    fun `detectReleasesFile finds uppercase RELEASES`() {
        val assets = listOf(
            AssetSnapshot("app-1.0.nupkg", "url1"),
            AssetSnapshot("RELEASES", "url2"),
        )
        assertEquals("RELEASES", detectReleasesFile(assets))
    }

    @Test
    fun `detectReleasesFile returns empty when no RELEASES`() {
        val assets = listOf(
            AssetSnapshot("app-1.0.nupkg", "url1"),
            AssetSnapshot("checksums.txt", "url2"),
        )
        assertEquals("", detectReleasesFile(assets))
    }

    @Test
    fun `detectReleasesFile is case sensitive`() {
        val assets = listOf(
            AssetSnapshot("app-1.0.nupkg", "url1"),
            AssetSnapshot("releases", "url2"),
            AssetSnapshot("RELEASES", "url3"),
        )
        assertEquals("RELEASES", detectReleasesFile(assets))
    }

    @Test
    fun `detectReleasesFile returns empty on empty list`() {
        assertEquals("", detectReleasesFile(emptyList()))
    }

    // ---------------------------------------------------------------
    // detectChecksumsFile (patched patterns)
    // ---------------------------------------------------------------

    @Test
    fun `detectChecksumsFile matches sha256sums dot txt`() {
        val assets = listOf(AssetSnapshot("sha256sums.txt", "url1"))
        assertEquals("sha256sums.txt", detectChecksumsFile(assets))
    }

    @Test
    fun `detectChecksumsFile matches checksums dot txt`() {
        val assets = listOf(AssetSnapshot("checksums.txt", "url1"))
        assertEquals("checksums.txt", detectChecksumsFile(assets))
    }

    @Test
    fun `detectChecksumsFile matches checksums txt via extensions`() {
        val assets = listOf(AssetSnapshot("app-1.0.zip.sha256", "url1"))
        assertEquals("app-1.0.zip.sha256", detectChecksumsFile(assets))
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
