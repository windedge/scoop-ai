package com.scoopai.tools

import com.scoopai.core.tools.api.ScoopVerifier
import com.scoopai.core.AssistHost
import com.scoopai.core.ScoopManifestGenerator
import com.scoopai.core.SessionCallbacks
import com.scoopai.core.artifacts.SensitiveArtifactWriter
import com.scoopai.tools.archive.ZipArchiveInspector
import com.scoopai.tools.cache.ScoopCacheManager
import com.scoopai.tools.config.OPENAI_COMPATIBLE_BASE_URL_REQUIRED
import com.scoopai.tools.config.OPENAI_COMPATIBLE_PROVIDER
import com.scoopai.tools.config.ResolvedProviderConfig
import com.scoopai.tools.config.UsedAppNameScanner
import com.scoopai.tools.config.resolveProviderConfig
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
        /**
         * Aggregate engine callbacks (phase start, phase output, tool activity).
         * The preferred form; flat callback parameters below are merged with it.
         */
        callbacks: SessionCallbacks? = null,
        /**
         * Aggregate assist host capabilities (save / install / read-reference
         * tools). The preferred form; flat host handlers below are merged with it.
         */
        host: AssistHost? = null,
        onPhase: ((String) -> Unit)? = null,
        artifacts: SensitiveArtifactWriter? = null,
        usedAppNamesProvider: (suspend () -> Set<String>)? = UsedAppNameScanner::scanDefault,
        /**
         * Already resolved provider config. Callers that resolved earlier
         * (e.g. the CLI composition root) pass it here so one run resolves
         * exactly once; when null the factory resolves from [config].
         */
        resolved: ResolvedProviderConfig? = null,
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
        /**
         * Optional host install callback backing the assist session's `install_app`
         * tool. Signature: (bucket, name) -> result JSON where a null bucket means
         * direct-install the current manifest without saving it to a bucket. When
         * non-null the assist subgraph exposes the tool; when null (e.g. CLI) it
         * is absent.
         */
        installAppHandler: (suspend (bucket: String?, name: String) -> String)? = null,
        /**
         * Optional host read callback backing the assist session's
         * `read_manifest_reference` tool. Signature: (bucket, name) -> result
         * JSON. When non-null the assist subgraph exposes the tool; when null
         * (e.g. CLI) it is absent.
         */
        readManifestRefHandler: (suspend (bucket: String, name: String) -> String)? = null,
    ): ScoopManifestGenerator {
        val resolvedConfig = resolved ?: resolveProviderConfig(
            flagProvider = config.provider,
            flagApiKey = config.apiKey,
            flagBaseUrl = config.baseUrl,
            flagModel = config.model,
        )

        if (resolvedConfig.provider == OPENAI_COMPATIBLE_PROVIDER && resolvedConfig.baseUrl == null) {
            error(OPENAI_COMPATIBLE_BASE_URL_REQUIRED)
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
        // The assist probe reports the failure reason so the prompt can diagnose
        // "install test not triggered" complaints for the user.
        val scoopVerifier = ProcessScoopVerifier.tryCreate(cache = cache)
        val (assistVerifier, assistUnavailableReason) = ProcessScoopVerifier.tryCreateDetailed(cache = cache)

        return ScoopManifestGenerator(
            promptExecutor = resolvedConfig.createExecutor(),
            model = resolvedConfig.model,
            toolRegistry = toolRegistry,
            maxIterations = config.maxIterations,
            manifestIo = manifestIo,
            gitHubApi = gitHubApi,
            archiveInspector = archiveInspector,
            callbacks = SessionCallbacks(
                onPhase = onPhase ?: callbacks?.onPhase,
                onPhaseOutput = onPhaseOutput ?: callbacks?.onPhaseOutput,
                onToolActivity = onToolActivity ?: callbacks?.onToolActivity,
            ),
            host = AssistHost(
                saveToBucket = saveToBucketHandler ?: host?.saveToBucket,
                installApp = installAppHandler ?: host?.installApp,
                readManifestRef = readManifestRefHandler ?: host?.readManifestRef,
            ),
            artifacts = artifacts,
            usedAppNamesProvider = usedAppNamesProvider,
            scoopVerifier = scoopVerifier,
            assistVerifier = assistVerifier,
            assistTestUnavailableReason = assistUnavailableReason,
            saveHandler = saveToBucketHandler?.let(::saveHandlerAdapter),
            installHandler = installAppHandler,
            readManifestRefHandler = readManifestRefHandler,
            replyLanguage = config.replyLanguage,
        )
    }

    /** Adapts the flat host save callback (bucket, name, overwrite) to the tool's args type. */
    private fun saveHandlerAdapter(
        handler: suspend (bucket: String?, name: String?, overwrite: Boolean) -> String,
    ): suspend (com.scoopai.core.agent.SaveToBucketArgs) -> String =
        { handler(it.bucket, it.name, it.overwrite) }
}
