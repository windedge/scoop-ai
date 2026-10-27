package com.scoopai.cli

import ai.koog.agents.core.tools.ToolRegistry
import com.scoopai.core.ScoopManifestGenerator
import com.scoopai.tools.DefaultScoopAiFactory
import com.scoopai.tools.DefaultToolRegistryFactory
import com.scoopai.tools.ScoopAiConfig
import com.scoopai.tools.archive.ZipArchiveInspector
import com.scoopai.tools.cache.ScoopCacheManager
import com.scoopai.tools.config.ResolvedProviderConfig
import com.scoopai.tools.config.resolveGithubToken
import com.scoopai.tools.config.resolveProviderConfig
import com.scoopai.tools.github.JdkHttpGitHubApi
import com.scoopai.tools.manifest.FileManifestIo
import com.scoopai.tools.verify.ProcessScoopVerifier

/**
 * Composition root for the scoop-ai CLI.
 *
 * Holds CLI-facing dependencies as [by lazy] properties.
 * [createGenerator] maps [EnvArgs] into [ScoopAiConfig] and delegates to [DefaultScoopAiFactory].
 *
 * @param envArgs Parsed CLI arguments.
 * @param env Environment variable map (defaults to [System.getenv]).
 */
class CompositionRoot(
    private val envArgs: EnvArgs,
    private val env: Map<String, String> = System.getenv(),
) {
    val resolvedAppName: String by lazy {
        envArgs.appName ?: inferAppName(envArgs.url)
    }

    val manifestIo: FileManifestIo by lazy {
        FileManifestIo(
            defaultAppName = resolvedAppName.ifBlank { null },
            persistMode = envArgs.persistMode,
        )
    }

    val cache: ScoopCacheManager by lazy {
        ScoopCacheManager(keepCache = envArgs.keepCache)
    }

    val gitHubApi: JdkHttpGitHubApi by lazy {
        JdkHttpGitHubApi(githubToken = effectiveGithubToken())
    }

    val archiveInspector: ZipArchiveInspector by lazy {
        ZipArchiveInspector(cache)
    }

    val scoopVerifier: ProcessScoopVerifier? by lazy {
        ProcessScoopVerifier.tryCreate()
    }

    val toolRegistry: ToolRegistry by lazy {
        DefaultToolRegistryFactory.create(
            cache = cache,
            githubToken = effectiveGithubToken(),
            gitHubApi = gitHubApi,
            manifestIo = manifestIo,
        )
    }

    /** Priority: `--github-token` > `SCOOP_AI_GITHUB_TOKEN` > `GITHUB_TOKEN` > null. */
    fun effectiveGithubToken(): String? =
        resolveGithubToken(flagGithubToken = envArgs.githubToken) { key -> env[key] }

    /**
     * Resolve LLM provider config from CLI flags and env fallbacks.
     *
     * @throws com.scoopai.cli.error.ConfigError if openai-compatible lacks a base URL.
     */
    fun resolveProviderConfig(): ResolvedProviderConfig {
        try {
            return com.scoopai.tools.config.resolveProviderConfig(
                flagProvider = envArgs.provider,
                flagApiKey = envArgs.apiKey,
                flagBaseUrl = envArgs.baseUrl,
                flagModel = envArgs.modelName,
                flagGithubToken = effectiveGithubToken(),
                env = { key -> env[key] },
            )
        } catch (e: Exception) {
            throw com.scoopai.cli.error.ConfigError(
                e.message ?: e.javaClass.simpleName, e
            )
        }
    }

    /**
     * Validate provider config with CLI [com.scoopai.cli.error.ConfigError], then assemble
     * via [DefaultScoopAiFactory] so CLI and library hosts share one path.
     */
    fun createGenerator(
        /** Aggregate engine callbacks (phase start, phase output, tool activity). */
        callbacks: com.scoopai.core.SessionCallbacks? = null,
        artifacts: com.scoopai.core.artifacts.SensitiveArtifactWriter? = null,
    ): ScoopManifestGenerator {
        val resolved = resolveProviderConfig()

        return DefaultScoopAiFactory.createGenerator(
            config = ScoopAiConfig(
                provider = envArgs.provider,
                apiKey = envArgs.apiKey,
                baseUrl = envArgs.baseUrl,
                model = envArgs.modelName,
                githubToken = effectiveGithubToken(),
                appName = resolvedAppName.ifBlank { null },
                persistMode = envArgs.persistMode,
                keepCache = envArgs.keepCache,
                nonInteractive = envArgs.nonInteractive,
            ),
            resolved = resolved,
            callbacks = callbacks,
            artifacts = artifacts,
        )
    }

    companion object {
        private fun inferAppName(url: String): String {
            val trimmed = url.trimEnd('/')
            val githubMatch = Regex("""github\.com/[^/]+/([^/?#]+)""").find(trimmed)
            if (githubMatch != null) return githubMatch.groupValues[1].lowercase()
            return trimmed.substringAfterLast("/")
                .substringBefore("?")
                .substringBefore("#")
                .lowercase()
        }
    }
}
