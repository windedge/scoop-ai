package com.scoopai.cli

import com.scoopai.cli.error.ConfigError
import com.scoopai.tools.manifest.FileManifestIo
import com.scoopai.tools.cache.ScoopCacheManager
import com.scoopai.tools.github.JdkHttpGitHubApi
import com.scoopai.tools.archive.ZipArchiveInspector
import com.scoopai.tools.verify.ProcessScoopVerifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompositionRootTest {

    // ---------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------

    @Test
    fun `construct with envArgs and emptyMap succeeds`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo")
        val root = CompositionRoot(envArgs, emptyMap())
        assertNotNull(root)
    }

    @Test
    fun `construct with default env map succeeds`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo")
        val root = CompositionRoot(envArgs)
        assertNotNull(root)
    }

    // ---------------------------------------------------------------
    // By-lazy dependencies
    // ---------------------------------------------------------------

    @Test
    fun `manifestIo is accessible and is FileManifestIo`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo")
        val root = CompositionRoot(envArgs, emptyMap())
        val io = root.manifestIo
        assertTrue(io is FileManifestIo)
    }

    @Test
    fun `cache is accessible and is ScoopCacheManager`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo")
        val root = CompositionRoot(envArgs, emptyMap())
        val cache = root.cache
        assertTrue(cache is ScoopCacheManager)
    }

    @Test
    fun `gitHubApi is accessible and is JdkHttpGitHubApi`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo")
        val root = CompositionRoot(envArgs, emptyMap())
        val api = root.gitHubApi
        assertTrue(api is JdkHttpGitHubApi)
    }

    @Test
    fun `archiveInspector is accessible and is ZipArchiveInspector`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo")
        val root = CompositionRoot(envArgs, emptyMap())
        val inspector = root.archiveInspector
        assertTrue(inspector is ZipArchiveInspector)
    }

    @Test
    fun `scoopVerifier is attempted without any config switch`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo")
        val root = CompositionRoot(envArgs, emptyMap())
        // The verify switch is gone: probing scoop is unconditional. The lazy
        // property must therefore resolve exactly like a bare scoop probe.
        val expected = ProcessScoopVerifier.tryCreate() != null
        assertEquals(expected, root.scoopVerifier != null)
    }

    @Test
    fun `toolRegistry is accessible`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo")
        val root = CompositionRoot(envArgs, emptyMap())
        val registry = root.toolRegistry
        assertNotNull(registry)
        assertTrue(registry.tools.isNotEmpty())
    }

    // ---------------------------------------------------------------
    // resolvedAppName
    // ---------------------------------------------------------------

    @Test
    fun `resolvedAppName infers from URL when appName not given`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/MyApp")
        val root = CompositionRoot(envArgs, emptyMap())
        assertEquals("myapp", root.resolvedAppName)
    }

    @Test
    fun `resolvedAppName uses appName from args when given`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo", appName = "custom-name")
        val root = CompositionRoot(envArgs, emptyMap())
        assertEquals("custom-name", root.resolvedAppName)
    }

    // ---------------------------------------------------------------
    // effectiveGithubToken
    // ---------------------------------------------------------------

    @Test
    fun `effectiveGithubToken returns null when neither envArgs nor env has token`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo")
        val root = CompositionRoot(envArgs, emptyMap())
        assertNull(root.effectiveGithubToken())
    }

    @Test
    fun `effectiveGithubToken returns envArgs dot githubToken when set`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo", githubToken = "ghp_cli123")
        val root = CompositionRoot(envArgs, emptyMap())
        assertEquals("ghp_cli123", root.effectiveGithubToken())
    }

    @Test
    fun `effectiveGithubToken falls back to env map SCOOP_AI_GITHUB_TOKEN`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo")
        val root = CompositionRoot(envArgs, mapOf("SCOOP_AI_GITHUB_TOKEN" to "ghp_env123"))
        assertEquals("ghp_env123", root.effectiveGithubToken())
    }

    @Test
    fun `effectiveGithubToken falls back to env map GITHUB_TOKEN`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo")
        val root = CompositionRoot(envArgs, mapOf("GITHUB_TOKEN" to "ghp_fallback123"))
        assertEquals("ghp_fallback123", root.effectiveGithubToken())
    }

    @Test
    fun `effectiveGithubToken prioritizes envArgs over env`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo", githubToken = "ghp_cli")
        val root = CompositionRoot(envArgs, mapOf("SCOOP_AI_GITHUB_TOKEN" to "ghp_env"))
        assertEquals("ghp_cli", root.effectiveGithubToken())
    }

    @Test
    fun `effectiveGithubToken prioritizes SCOOP_AI_GITHUB_TOKEN over GITHUB_TOKEN`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo")
        val root = CompositionRoot(envArgs, mapOf(
            "SCOOP_AI_GITHUB_TOKEN" to "ghp_scoop",
            "GITHUB_TOKEN" to "ghp_plain"
        ))
        assertEquals("ghp_scoop", root.effectiveGithubToken())
    }

    // ---------------------------------------------------------------
    // resolveProviderConfig
    // ---------------------------------------------------------------

    @Test
    fun `resolveProviderConfig with default provider and api-key does not throw`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo", apiKey = "test-key")
        val root = CompositionRoot(envArgs, emptyMap())
        val config = root.resolveProviderConfig()
        assertNotNull(config)
    }

    @Test
    fun `resolveProviderConfig with openai-compatible missing baseUrl throws ConfigError`() {
        val envArgs = EnvArgs(
            url = "https://github.com/owner/repo",
            provider = "openai-compatible",
            apiKey = "test-key",
        )
        val root = CompositionRoot(envArgs, emptyMap())
        val ex = assertThrows(ConfigError::class.java) {
            root.resolveProviderConfig()
        }
        assertTrue(ex.message?.contains("base-url") == true || ex.message?.contains("BASE_URL") == true)
    }

    @Test
    fun `resolveProviderConfig with openai-compatible and baseUrl succeeds`() {
        val envArgs = EnvArgs(
            url = "https://github.com/owner/repo",
            provider = "openai-compatible",
            apiKey = "test-key",
            baseUrl = "http://localhost:8080/v1",
        )
        val root = CompositionRoot(envArgs, emptyMap())
        val config = root.resolveProviderConfig()
        assertNotNull(config)
    }

    // ---------------------------------------------------------------
    // createGenerator
    // ---------------------------------------------------------------

    @Test
    fun `createGenerator returns non-null generator`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo", apiKey = "test-key")
        val root = CompositionRoot(envArgs, emptyMap())
        val generator = root.createGenerator()
        assertNotNull(generator)
    }

    @Test
    fun `createGenerator with onPhase callback`() {
        val envArgs = EnvArgs(url = "https://github.com/owner/repo", apiKey = "test-key")
        val root = CompositionRoot(envArgs, emptyMap())
        var invoked = false
        val generator = root.createGenerator(onPhase = { name -> invoked = true })
        assertNotNull(generator)
        // onPhase is not called during construction, only during generate()
    }

    // ---------------------------------------------------------------
    // By-lazy semantics - verify dependency chain builds correctly
    // ---------------------------------------------------------------

    @Test
    fun `accessing all by-lazy properties does not throw`() {
        val envArgs = EnvArgs(
            url = "https://github.com/owner/repo",
            apiKey = "test-key",
            baseUrl = "http://localhost:8080/v1",
        )
        val root = CompositionRoot(envArgs, emptyMap())
        // Access all lazy properties to ensure they initialize
        assertNotNull(root.manifestIo)
        assertNotNull(root.cache)
        assertNotNull(root.gitHubApi)
        assertNotNull(root.archiveInspector)
        assertNotNull(root.toolRegistry)
    }
}
