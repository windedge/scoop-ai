package com.scoopai.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.scoopai.cli.CompositionRoot
import com.scoopai.cli.ConsoleProgress
import com.scoopai.cli.EnvArgs
import com.scoopai.cli.JsonFormatter
import com.scoopai.cli.error.CliError
import com.scoopai.cli.error.ExitCode
import com.scoopai.cli.error.GeneralError
import com.scoopai.cli.error.NoAssetsError
import com.scoopai.cli.error.UsageError
import com.scoopai.cli.error.classifyException
import com.scoopai.cli.error.mask
import com.scoopai.core.ManifestResult
import com.scoopai.core.manifest.parseManifestMeta
import com.scoopai.core.artifacts.CheckpointResume
import com.scoopai.tools.DefaultToolRegistryFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Clikt command that generates a Scoop manifest from a GitHub URL or project URL.
 *
 * All CLI flags are declared using clikt [option]/[flag]/[choice].
 * The [run] method performs the actual generation logic.
 */
open class GenerateCommand(
    name: String = "generate",
    help: String = "Generate a Scoop manifest from a GitHub URL or project URL",
) : CliktCommand(
    name = name,
    help = help,
    invokeWithoutSubcommand = true,
) {

    private val ARTIFACTS_BASE_DIR = System.getProperty("user.dir", ".")
    // ── Positional argument ───────────────────────────────────────────
    val url: String? by argument("url", help = "GitHub repository URL or project URL")

    // ── String options (nullable by default, no default → null if omitted) ──
    val output: String by option("-o", "--output").help("Output file path (default: bucket/<appName>.json)").default("")
    val provider: String by option("-P", "--provider").help("LLM provider: deepseek|openai|anthropic|ollama|openrouter|openai-compatible").default("")
    val apiKey: String by option("--api-key").help("API key (default: SCOOP_AI_API_KEY or provider-specific env var)").default("")
    val baseUrl: String? by option("--base-url").help("Custom API base URL (required for openai-compatible)")
    val model: String? by option("-m", "--model").help("LLM model name (overrides provider default)")
    val appName: String? by option("--app-name").help("App name for cache (default: inferred from URL)")
    val prompt: String? by option("-p", "--prompt").help("Additional requirements for manifest generation")
    val githubToken: String? by option("--github-token").help("GitHub personal access token (avoids rate limits)")

    // ── Boolean flags ─────────────────────────────────────────────────
    val verify: Boolean by option("--verify", "-V").help("Verify manifest by running scoop install").flag()
    val nonInteractive: Boolean by option("-n", "--non-interactive").help("Skip __ask_user__ prompts and use defaults (for batch/CI)").flag()
    val debug: Boolean by option("--debug").help("Print stack traces on errors").flag()
    val noProgress: Boolean by option("--no-progress").help("Disable TTY progress overlay").flag()

    // ── Cache options ─────────────────────────────────────────────────
    /**
     * Cache retention policy.
     * - "keep": retain downloaded files in Scoop cache (default)
     * - "discard": delete downloaded files after generation
     *
     * [--no-cache] is retained as a deprecated alias for [--cache=discard].
     * Uses a unique primary name to avoid clikt 4.x auto-creating a negated
     * --cache flag (which would conflict with the choice option below).
     */
    private val _noCacheFlag: Boolean by option("--no-cache-flag", "--no-cache", hidden = true).flag()
    val noCache: Boolean get() = _noCacheFlag

    /**
     * Cache mode option. When set explicitly, takes priority over --no-cache.
     */
    val cache: String by option("--cache")
        .help("Cache mode: keep|discard downloaded files (default: keep)")
        .choice("keep", "discard")
        .default("keep")

    /**
     * Derived property: whether to keep downloaded files in Scoop cache.
     *
     * Resolution order:
     * 1. `--cache=discard` => false
     * 2. `--no-cache` (backward compat) => false
     * 3. Default => true
     */
    internal val keepCache: Boolean get() = when {
        cache != "keep" -> false
        _noCacheFlag -> false
        else -> true
    }

    // ── Output format ──────────────────────────────────────────────
    val outputFormat: String by option("--output-format")
        .help("Output format: text|json (json prints structured ManifestResult)")
        .choice("text", "json")
        .default("text")

    // ── Artifacts mode ────────────────────────────────────────────
    /**
     * Debug artifact writing mode.
     * - "auto": write only on failure or when --non-interactive is set
     * - "always": always write phase outputs to .scoopai/runs/<runId>/
     * - "never": do not write any artifacts
     */
    val artifactsMode: String by option("--artifacts")
        .help("Write phase artifacts: auto|always|never (default: auto)")
        .choice("auto", "always", "never")
        .default("auto")

    /**
     * Run identifier for artifacts directory.
     * Auto-generated as UUID if not specified.
     */
    val runId: String? by option("--run-id")
        .help("Override run ID for artifacts and checkpoint resume")

    /**
     * Checkpoint resume stage.
     * When set, skips completed phases and resumes from the specified stage.
     * Requires --run-id to identify the artifacts directory.
     * - "analyze": skip research phase, start from analyze
     * - "generate": skip research + analyze phases, start from generate
     */
    val from: String by option("--from")
        .help("Resume from stage: analyze|generate (requires --run-id)")
        .choice("analyze", "generate")
        .default("")

    // ── Choice option (persist mode) ──────────────────────────────────
    val persist: String by option("-s", "--persist")
        .help("Persist field policy: auto|force|none (default: auto)")
        .choice("auto", "force", "none", "no")
        .default("auto")

    // ── Run ───────────────────────────────────────────────────────────
    override fun run() {
        // Print deprecation warning for --no-cache
        if (_noCacheFlag) {
            echo(NO_CACHE_DEPRECATION_WARNING)
        }

        doRun()
    }

    /**
     * Core execution logic, separated so ScoopAi can share it.
     */
    internal fun doRun() {
        var effectiveDebug = debug
        val isJsonOutput = outputFormat == "json"
        try {
            // ── URL format validation (must be before resolveProviderConfig) ──
            val rawUrl = url
            if (rawUrl != null && rawUrl.none { it in "./:" }) {
                throw UsageError("Invalid URL format: $rawUrl. " +
                    "Expected http(s)://... or github.com/owner/repo")
            }

            // ── Checkpoint resume validation (must be before resolveProviderConfig) ──
            if (from.isNotEmpty() && runId.isNullOrEmpty()) {
                throw UsageError("--from requires --run-id to be specified. " +
                    "Example: --from analyze --run-id <previous-run-id>")
            }

            // ── Build EnvArgs from parsed clikt values ────────────────
            val envArgs = buildEnvArgs()

            // Also check env var for debug
            if (!effectiveDebug && System.getenv("SCOOP_AI_DEBUG")?.lowercase() == "true") {
                effectiveDebug = true
            }

            // ── Build dependency graph ─────────────────────────────────
            val compositionRoot = CompositionRoot(envArgs)

            // ── Resolve provider config ────────────────────────────────
            val config = compositionRoot.resolveProviderConfig()

            // ── Inject persist mode into userPrompt ────────────────────
            val persistInstruction = buildPersistInstruction(persist)
            val effectiveUserPrompt = listOfNotNull(prompt, persistInstruction)
                .joinToString("\n\n")
                .ifBlank { null }

            // ── Set non-interactive mode ───────────────────────────────
            if (nonInteractive) {
                System.setProperty("SCOOP_AI_NON_INTERACTIVE", "true")
            }

            // ── Progress overlay (disabled in JSON mode) ───────────────
            val progressEnabled = !isJsonOutput && !noProgress && System.console() != null
            val progress = ConsoleProgress(
                enabled = progressEnabled,
            )

            // ── Resolve output ─────────────────────────────────────────
            val resolvedOutput = output.ifBlank {
                System.getenv("SCOOP_AI_OUTPUT") ?: ""
            }

            // ── Setup debug artifacts ─────────────────────────────────
            val effectiveRunId = runId ?: java.util.UUID.randomUUID().toString()
            val rawArtifacts = when (artifactsMode) {
                "never" -> null
                else -> com.scoopai.core.artifacts.RunArtifacts(
                    runId = effectiveRunId,
                    baseDir = ARTIFACTS_BASE_DIR,
                )
            }
            // Wrap in SensitiveArtifactWriter for masking
            val sensitiveArtifacts = rawArtifacts?.let { raw ->
                com.scoopai.core.artifacts.SensitiveArtifactWriter(raw) { text ->
                    com.scoopai.cli.error.mask(text)
                }
            }

            // ── Checkpoint resume ─────────────────────────────────────
            val shouldResume = from.isNotEmpty()

            progress.start()
            val result: ManifestResult = try {
                runBlocking {
                    val generator = compositionRoot.createGenerator(
                        onPhase = { name -> progress.phase(name) },
                        artifacts = sensitiveArtifacts,
                    )
                    // Reset tool call budget for a fresh run
                    DefaultToolRegistryFactory.getToolBudget()?.reset()

                    // ── Load checkpoint resume data if requested ─────
                    val effectiveResumeData = if (shouldResume) {
                        val runDir = File(ARTIFACTS_BASE_DIR, ".scoopai/runs/$runId")
                        val stage = when (from) {
                            "analyze" -> CheckpointResume.ResumeStage.ANALYZE
                            "generate" -> CheckpointResume.ResumeStage.GENERATE
                            else -> null
                        }
                        if (stage != null) {
                            val data = CheckpointResume(runDir).loadResumePoint(stage)
                            if (data == null) {
                                logger.warn { "[resume] Artifacts not found or corrupt for " +
                                    "runId=$runId, stage=$from. Falling back to full generation." }
                            } else {
                                logger.info { "[resume] Loaded checkpoint for $from (runId=$runId)" }
                            }
                            data
                        } else null
                    } else null

                    generator.generate(
                        url = envArgs.url,
                        outputPath = resolvedOutput.ifBlank { null },
                        userPrompt = effectiveUserPrompt,
                        resumeData = effectiveResumeData,
                    )
                }
            } finally {
                progress.done()
            }

            // ── Handle result ──────────────────────────────────────────
            val manifest = result.manifest
            if (result.noAssets) {
                if (isJsonOutput) {
                    echo(JsonFormatter.format(result))
                }
                throw NoAssetsError("No Windows assets found for the given project.")
            }
            if (manifest.isBlank()) {
                throw GeneralError("no manifest generated")
            }

            // ── JSON mode: output JSON to stdout, still write file ─────
            if (isJsonOutput) {
                echo(JsonFormatter.format(result))
            } else {
                // Print suggested app name when available
                if (result.suggestedAppName != null) {
                    echo("Suggested app name: ${result.suggestedAppName}")
                }
            }

            // Write manifest to file (always, even in JSON mode)
            val appNameForOutput = result.suggestedAppName?.ifBlank { null }
                ?: compositionRoot.resolvedAppName
            val writePath = output.ifBlank { "bucket/$appNameForOutput.json" }
            compositionRoot.manifestIo.write(manifest, writePath)

            if (!isJsonOutput) {
                echo("Written to $writePath")
                val absoluteWritePath = java.io.File(writePath).absoluteFile.canonicalPath
                echo("Absolute path: $absoluteWritePath")

                // ── Print token usage ──────────────────────────────────
                result.tokenUsage?.let { usage ->
                    echo("Token usage: input=${usage.totalInputTokens} output=${usage.totalOutputTokens} total=${usage.totalTokens}")
                }
                if (result.durationMs > 0) {
                    val seconds = result.durationMs / 1000
                    val minutes = seconds / 60
                    val sec = seconds % 60
                    echo("Duration: ${minutes}m ${sec}s (${result.durationMs}ms)")
                }
            }

            // Finalize cache entries (prefer registry cache from DefaultScoopAiFactory)
            if (envArgs.keepCache) {
                val parsed = parseManifestMeta(manifest)
                val pVersion = parsed.version
                val pUrl = parsed.url
                logger.info { "[cache] Finalizing: app=$appNameForOutput version=$pVersion url=$pUrl" }
                if (pVersion != null && pUrl != null) {
                    val cache = DefaultToolRegistryFactory.getCacheManager()
                        ?: compositionRoot.cache
                    val finalized = cache.finalizeCacheEntry(
                        pUrl, appNameForOutput, pVersion
                    )
                    if (finalized != null && !isJsonOutput) {
                        echo("Cache: ${finalized.toAbsolutePath()}")
                        echo("scoop install $appNameForOutput will reuse this file.")
                    } else if (finalized == null) {
                        logger.warn { "[cache] No cached file found for $pUrl" }
                    }
                } else {
                    logger.warn { "[cache] Could not parse manifest: version=$pVersion url=$pUrl" }
                }
            }

        } catch (e: CliError) {
            echo(mask("ERROR: ${e::class.simpleName}: ${e.message}"), err = true)
            if (effectiveDebug) echo(mask(e.stackTraceToString()), err = true)
            throw ProgramResult(e.exit.code)
        } catch (e: Exception) {
            val cliError = classifyException(e)
            echo(mask("ERROR: ${cliError::class.simpleName}: ${cliError.message}"), err = true)
            if (effectiveDebug) echo(mask(e.stackTraceToString()), err = true)
            throw ProgramResult(cliError.exit.code)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /**
     * Convert clikt-parsed values to [EnvArgs].
     *
     * Cache mode resolution (priority order):
     * 1. `--cache=discard` => keepCache=false
     * 2. `--no-cache` (backward compat) => keepCache=false
     * 3. Default (no flags) => keepCache=true
     */
    internal fun buildEnvArgs(): EnvArgs {
        // Normalize persist: "no" => "none" for backward compat with old EnvArgs
        val normalizedPersist = when (persist) {
            "no" -> "none"
            else -> persist
        }
        return EnvArgs(
            url = url ?: "",  // handled as required by clikt; fallback for type safety

            output = output,
            provider = provider,
            apiKey = apiKey,
            baseUrl = baseUrl,
            modelName = model,
            verify = verify,
            appName = appName,
            keepCache = keepCache,
            userPrompt = prompt,
            nonInteractive = nonInteractive,
            persistMode = normalizedPersist,
            githubToken = githubToken,
            debug = debug,
            noProgress = noProgress,
        )
    }

    /**
     * Build a persist-mode instruction fragment to inject into the user prompt.
     */
    private fun buildPersistInstruction(persistMode: String): String? {
        return when (persistMode) {
            "force" -> (
                "PERSIST REQUIREMENT (MANDATORY): You MUST include a 'persist' field in the Scoop manifest. " +
                    "Identify config/data directories or files (from persist_targets in the research output, " +
                    "or from source code analysis) and add them to 'persist'. " +
                    "Do NOT generate a manifest without the 'persist' field. " +
                    "If no persist targets were found in the research phase, persist the most likely config " +
                    "directory based on the project type."
                )
            "none" -> (
                "PERSIST REQUIREMENT (MANDATORY): Do NOT include any 'persist' field in the Scoop manifest. " +
                    "The manifest must NOT have a 'persist' key. " +
                    "This overrides any other instructions about persist discovery. " +
                    "In the Research phase: skip step 6 (persist discovery) entirely, do NOT read source code " +
                    "for the purpose of finding persist targets. " +
                    "Set persist_targets to an empty list and env_targets to an empty map. " +
                    "In the Analyze phase: set persist to an empty list, do NOT add pre_install scripts " +
                    "for persist directories. " +
                    "In the Generate phase: the final manifest must NOT contain a 'persist' key."
                )
            else -> null // auto: no instruction
        }
    }

    companion object {
        /** Deprecation warning text for --no-cache flag. Exposed as constant for test verification. */
        internal const val NO_CACHE_DEPRECATION_WARNING =
            "WARNING: --no-cache is deprecated. Use --cache=discard instead."
    }
}
