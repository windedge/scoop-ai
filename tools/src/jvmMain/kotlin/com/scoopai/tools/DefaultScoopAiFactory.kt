package com.scoopai.tools

import com.scoopai.core.tools.api.ScoopVerifier
import com.scoopai.core.ScoopManifestGenerator
import com.scoopai.core.artifacts.SensitiveArtifactWriter
import com.scoopai.tools.archive.ZipArchiveInspector
import com.scoopai.tools.cache.ScoopCacheManager
import com.scoopai.tools.config.ProviderConfigResolver
import com.scoopai.tools.config.UsedAppNameScanner
import com.scoopai.tools.github.JdkHttpGitHubApi
import com.scoopai.tools.interaction.Question
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
         * analyze / generate / assist / repair). Forwarded to
         * [ScoopManifestGenerator]; intended for in-memory checkpoints only.
         */
        onPhaseOutput: ((String, String) -> Unit)? = null,
        /**
         * Real-time listener for agent tool activity (started / completed / failed).
         * Forwarded to [ScoopManifestGenerator].
         */
        onToolActivity: ((com.scoopai.core.ToolActivity) -> Unit)? = null,
        /**
         * Optional suspend callback for AskUser tool interaction.
         */
        askUserHandler: (suspend (String) -> String)? = null,
        /**
         * Optional questionnaire callback for AskUser tool interaction.
         */
        questionnaireHandler: (suspend (List<Question>) -> String)? = null,
        /**
         * Optional host save callback backing the assist session's `save_to_bucket`
         * tool. Signature: (bucket, name, overwrite) -> result JSON. When non-null
         * the assist subgraph exposes the tool; when null (e.g. CLI) it is absent.
         */
        saveToBucketHandler: (suspend (bucket: String?, name: String?, overwrite: Boolean) -> String)? = null,
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
        val toolRegistry = DefaultToolRegistryFactory.create(
            cache = cache,
            githubToken = config.githubToken,
            gitHubApi = gitHubApi,
            manifestIo = manifestIo,
            nonInteractive = config.nonInteractive,
            askUserHandler = askUserHandler,
            questionnaireHandler = questionnaireHandler,
            skipDownloadConfirm = config.skipDownloadConfirm,
        )

        // Install verification is always on: probe scoop unconditionally. Two
        // instances are created so the review agent and the assist/repair subgraph
        // each get their own verifier with an independent install budget; when
        // scoop is unavailable both stay null and the subgraphs run tool-free.
        fun verifier() = ProcessScoopVerifier.tryCreate(cache = cache)
        val scoopVerifier = verifier()
        val assistVerifier = verifier()

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
            scoopVerifier = scoopVerifier,
            assistVerifier = assistVerifier,
            saveHandler = saveToBucketHandler?.let(::saveHandlerAdapter),
        )
    }

    /** Adapts the flat host save callback (bucket, name, overwrite) to the tool's args type. */
    private fun saveHandlerAdapter(
        handler: suspend (bucket: String?, name: String?, overwrite: Boolean) -> String,
    ): suspend (com.scoopai.core.agent.SaveToBucketArgs) -> String =
        { handler(it.bucket, it.name, it.overwrite) }
}
