package com.scoopai.tools

import com.scoopai.core.ScoopManifestGenerator
import com.scoopai.core.artifacts.SensitiveArtifactWriter
import com.scoopai.tools.archive.ZipArchiveInspector
import com.scoopai.tools.cache.ScoopCacheManager
import com.scoopai.tools.config.ProviderConfigResolver
import com.scoopai.tools.config.UsedAppNameScanner
import com.scoopai.tools.github.JdkHttpGitHubApi
import com.scoopai.tools.manifest.FileManifestIo
import com.scoopai.tools.verify.ProcessScoopVerifier

/**
 * Library entry point for assembling a fully wired [ScoopManifestGenerator].
 *
 * Desktop hosts (e.g. Scooper) can depend on `:tools` without Clikt / EnvArgs.
 * CLI maps [ScoopAiConfig] from EnvArgs and calls the same path.
 */
object DefaultScoopAiFactory {

    fun createGenerator(
        config: ScoopAiConfig,
        onPhase: ((String) -> Unit)? = null,
        artifacts: SensitiveArtifactWriter? = null,
        usedAppNamesProvider: (suspend () -> Set<String>)? = UsedAppNameScanner::scanDefault,
        /**
         * In-memory listener for raw, un-masked phase output JSON (research /
         * analyze / generate / refine). Forwarded to
         * [ScoopManifestGenerator]; intended for in-memory checkpoints only.
         */
        onPhaseOutput: ((String, String) -> Unit)? = null,
        /**
         * Real-time listener for agent tool activity (started / completed / failed).
         * Forwarded to [ScoopManifestGenerator].
         */
        onToolActivity: ((com.scoopai.core.ToolActivity) -> Unit)? = null,
    ): ScoopManifestGenerator {
        val resolved = ProviderConfigResolver.resolve(
            cliProvider = config.provider,
            cliApiKey = config.apiKey,
            cliBaseUrl = config.baseUrl,
            cliModel = config.model,
        )

        if (resolved.provider == "openai-compatible" && resolved.baseUrl == null) {
            error("baseUrl or SCOOP_AI_BASE_URL is required when using openai-compatible provider.")
        }

        val cache = ScoopCacheManager(keepCache = config.keepCache)
        val gitHubApi = JdkHttpGitHubApi(githubToken = config.githubToken)
        val archiveInspector = ZipArchiveInspector(cache)
        val manifestIo = FileManifestIo(
            defaultAppName = config.appName?.ifBlank { null },
            persistMode = config.persistMode,
        )
        val scoopVerifier = if (config.verify) ProcessScoopVerifier.tryCreate() else null

        val toolRegistry = DefaultToolRegistryFactory.create(
            cache = cache,
            githubToken = config.githubToken,
            gitHubApi = gitHubApi,
            manifestIo = manifestIo,
            scoopVerifier = scoopVerifier,
            nonInteractive = config.nonInteractive,
        )

        return ScoopManifestGenerator(
            promptExecutor = resolved.createExecutor(),
            model = resolved.model,
            toolRegistry = toolRegistry,
            maxIterations = config.maxIterations,
            manifestIo = manifestIo,
            gitHubApi = gitHubApi,
            archiveInspector = archiveInspector,
            onPhase = onPhase,
            onToolActivity = onToolActivity,
            phaseOutputListener = onPhaseOutput,
            artifacts = artifacts,
            usedAppNamesProvider = usedAppNamesProvider,
        )
    }
}
