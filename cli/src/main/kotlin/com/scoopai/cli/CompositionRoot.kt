package com.scoopai.cli

import ai.koog.agents.core.tools.ToolRegistry
import com.scoopai.core.ScoopManifestGenerator
import com.scoopai.tools.DefaultToolRegistryFactory
import com.scoopai.tools.archive.ZipArchiveInspector
import com.scoopai.tools.cache.ScoopCacheManager
import com.scoopai.tools.config.ProviderConfigResolver
import com.scoopai.tools.config.ResolvedProviderConfig
import com.scoopai.tools.config.UsedAppNameScanner
import com.scoopai.tools.github.JdkHttpGitHubApi
import com.scoopai.tools.manifest.FileManifestIo
import com.scoopai.tools.verify.ProcessScoopVerifier
import kotlinx.coroutines.runBlocking

/**
 * Composition root for the scoop-ai CLI.
 *
 * Holds all top-level dependencies as [by lazy] properties so they are
 * created only when first accessed. Tests can inject a custom [env] map
 * to control environment-dependent resolution without touching real env vars.
 *
 * @param envArgs Parsed CLI arguments.
 * @param env Environment variable map (defaults to [System.getenv]).
 */
class CompositionRoot(
    private val envArgs: EnvArgs,
    private val env: Map<String, String> = System.getenv(),
) {
    // -----------------------------------------------------------------------
    // Derived values
    // -----------------------------------------------------------------------

    /**
     * App name resolved from CLI `--app-name` flag or inferred from the URL.
     */
    val resolvedAppName: String by lazy {
        envArgs.appName ?: inferAppName(envArgs.url)
    }

    // -----------------------------------------------------------------------
    // By-lazy dependencies (created on first access)
    // -----------------------------------------------------------------------

    /** Manifest I/O with the resolved app name and persist mode. */
    val manifestIo: FileManifestIo by lazy {
        FileManifestIo(
            defaultAppName = resolvedAppName.ifBlank { null },
            persistMode = envArgs.persistMode,
        )
    }

    /** Scoop cache manager. */
    val cache: ScoopCacheManager by lazy {
        ScoopCacheManager(keepCache = envArgs.keepCache)
    }

    /** GitHub API client. */
    val gitHubApi: JdkHttpGitHubApi by lazy {
        JdkHttpGitHubApi(githubToken = effectiveGithubToken())
    }

    /** Archive (ZIP) inspector backed by the cache. */
    val archiveInspector: ZipArchiveInspector by lazy {
        ZipArchiveInspector(cache)
    }

    /**
     * Scoop verifier for `scoop install` verification.
     * Only created when verification is enabled (flag or env var).
     */
    val scoopVerifier: ProcessScoopVerifier? by lazy {
        val envVerify = env["SCOOP_AI_VERIFY"]?.lowercase() == "true"
        val doVerify = envArgs.verify || envVerify
        if (doVerify) createScoopVerifier() else null
    }

    /** Full tool registry for the agent. */
    val toolRegistry: ToolRegistry by lazy {
        DefaultToolRegistryFactory.create(
            cache = cache,
            githubToken = effectiveGithubToken(),
            gitHubApi = gitHubApi,
            manifestIo = manifestIo,
            scoopVerifier = scoopVerifier,
        )
    }

    // -----------------------------------------------------------------------
    // Methods
    // -----------------------------------------------------------------------

    /**
     * Resolve the effective GitHub token from CLI flag, then env vars.
     *
     * Priority: `--github-token` > `SCOOP_AI_GITHUB_TOKEN` > `GITHUB_TOKEN` > null.
     */
    fun effectiveGithubToken(): String? {
        return envArgs.githubToken
            ?: env["SCOOP_AI_GITHUB_TOKEN"]
            ?: env["GITHUB_TOKEN"]
    }

    /**
     * Resolve the full LLM provider configuration from CLI flags
     * and environment variable fallbacks.
     *
     * @throws com.scoopai.cli.error.ConfigError if openai-compatible is used
     *         without a base URL.
     */
    fun resolveProviderConfig(): ResolvedProviderConfig {
        val config = try {
            ProviderConfigResolver.resolve(
                cliProvider = envArgs.provider,
                cliApiKey = envArgs.apiKey,
                cliBaseUrl = envArgs.baseUrl,
                cliModel = envArgs.modelName,
            )
        } catch (e: Exception) {
            throw com.scoopai.cli.error.ConfigError(
                e.message ?: e.javaClass.simpleName, e
            )
        }

        if (config.provider == "openai-compatible" && config.baseUrl == null) {
            throw com.scoopai.cli.error.ConfigError(
                "--base-url or SCOOP_AI_BASE_URL is required when using openai-compatible provider."
            )
        }

        return config
    }

    /**
     * Create a [ScoopManifestGenerator] wired with all dependencies
     * from this composition root.
     *
     * @param onPhase Optional callback invoked when a phase starts.
     * @param artifacts Optional sensitive artifact writer for debug output.
     */
    fun createGenerator(
        onPhase: ((String) -> Unit)? = null,
        artifacts: com.scoopai.core.artifacts.SensitiveArtifactWriter? = null,
    ): ScoopManifestGenerator {
        val config = resolveProviderConfig()
        return ScoopManifestGenerator(
            promptExecutor = config.createExecutor(),
            model = config.model,
            toolRegistry = toolRegistry,
            maxIterations = 500,
            manifestIo = manifestIo,
            gitHubApi = gitHubApi,
            archiveInspector = archiveInspector,
            onPhase = onPhase,
            artifacts = artifacts,
            usedAppNamesProvider = UsedAppNameScanner::scanDefault,
        )
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Try to create a [ProcessScoopVerifier] if `scoop --version` succeeds.
     */
    private fun createScoopVerifier(): ProcessScoopVerifier? {
        return try {
            val check = ProcessBuilder("scoop", "--version")
                .redirectErrorStream(true)
                .start()
            check.inputStream.bufferedReader().readText()
            check.waitFor()
            if (check.exitValue() == 0) ProcessScoopVerifier() else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /**
         * Extract app name from a GitHub URL (e.g. "https://github.com/owner/repo" → "repo").
         */
        private fun inferAppName(url: String): String {
            val trimmed = url.trimEnd('/')
            val githubMatch = Regex("""github\.com/[^/]+/([^/?#]+)""").find(trimmed)
            if (githubMatch != null) return githubMatch.groupValues[1].lowercase()
            // Fallback: last path segment
            return trimmed.substringAfterLast("/").substringBefore("?").substringBefore("#").lowercase()
        }
    }
}
