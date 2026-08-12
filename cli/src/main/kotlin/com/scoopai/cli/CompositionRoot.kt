package com.scoopai.cli

import ai.koog.agents.core.tools.ToolRegistry
import com.scoopai.core.ScoopManifestGenerator
import com.scoopai.tools.DefaultScoopAiFactory
import com.scoopai.tools.DefaultToolRegistryFactory
import com.scoopai.tools.ScoopAiConfig
import com.scoopai.tools.archive.ZipArchiveInspector
import com.scoopai.tools.cache.ScoopCacheManager
import com.scoopai.tools.config.ProviderConfigResolver
import com.scoopai.tools.config.ResolvedProviderConfig
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
        if (isVerifyEnabled()) ProcessScoopVerifier.tryCreate() else null
    }

    val toolRegistry: ToolRegistry by lazy {
        DefaultToolRegistryFactory.create(
            cache = cache,
            githubToken = effectiveGithubToken(),
            gitHubApi = gitHubApi,
            manifestIo = manifestIo,
            scoopVerifier = scoopVerifier,
        )
    }

    /** Priority: `--github-token` > `SCOOP_AI_GITHUB_TOKEN` > `GITHUB_TOKEN` > null. */
    fun effectiveGithubToken(): String? =
        envArgs.githubToken
            ?: env["SCOOP_AI_GITHUB_TOKEN"]
            ?: env["GITHUB_TOKEN"]

    /**
     * Resolve LLM provider config from CLI flags and env fallbacks.
     *
     * @throws com.scoopai.cli.error.ConfigError if openai-compatible lacks a base URL.
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
     * Validate provider config with CLI [com.scoopai.cli.error.ConfigError], then assemble
     * via [DefaultScoopAiFactory] so CLI and library hosts share one path.
     */
    fun createGenerator(
        onPhase: ((String) -> Unit)? = null,
        artifacts: com.scoopai.core.artifacts.SensitiveArtifactWriter? = null,
    ): ScoopManifestGenerator {
        resolveProviderConfig()

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
                verify = isVerifyEnabled(),
                nonInteractive = envArgs.nonInteractive,
            ),
            onPhase = onPhase,
            artifacts = artifacts,
        )
    }

    private fun isVerifyEnabled(): Boolean =
        envArgs.verify || env["SCOOP_AI_VERIFY"]?.lowercase() == "true"

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
