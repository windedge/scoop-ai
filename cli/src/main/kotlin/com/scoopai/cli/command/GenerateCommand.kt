package com.scoopai.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.scoopai.cli.CompositionRoot
import com.scoopai.cli.ConsoleProgress
import com.scoopai.cli.EnvArgs
import com.scoopai.cli.error.CliError
import com.scoopai.cli.error.ExitCode
import com.scoopai.cli.error.GeneralError
import com.scoopai.cli.error.NoAssetsError
import com.scoopai.cli.error.classifyException
import com.scoopai.cli.error.mask
import com.scoopai.core.ManifestResult
import com.scoopai.tools.DefaultToolRegistryFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

/**
 * Clikt command that generates a Scoop manifest from a GitHub URL or project URL.
 *
 * All 14 CLI flags are declared using clikt [option]/[flag]/[choice].
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
    // ── Positional argument ───────────────────────────────────────────
    val url: String? by argument("url")

    // ── String options (nullable by default, no default → null if omitted) ──
    val output: String by option("-o", "--output").default("")
    val provider: String by option("-P", "--provider").default("")
    val apiKey: String by option("--api-key").default("")
    val baseUrl: String? by option("--base-url")
    val model: String? by option("-m", "--model")
    val appName: String? by option("--app-name")
    val prompt: String? by option("-p", "--prompt")
    val githubToken: String? by option("--github-token")

    // ── Boolean flags ─────────────────────────────────────────────────
    val verify: Boolean by option("--verify", "-V").flag()
    val noCache: Boolean by option("--no-cache").flag()
    val nonInteractive: Boolean by option("-n", "--non-interactive").flag()
    val debug: Boolean by option("--debug").flag()
    val noProgress: Boolean by option("--no-progress").flag()

    // ── Choice option (persist mode) ──────────────────────────────────
    val persist: String by option("-s", "--persist")
        .choice("auto", "force", "none", "no")
        .default("auto")

    // ── Run ───────────────────────────────────────────────────────────
    override fun run() {
        doRun()
    }

    /**
     * Core execution logic, separated so ScoopAi can share it.
     */
    internal fun doRun() {
        var effectiveDebug = debug
        try {
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

            // ── Progress overlay ───────────────────────────────────────
            val progress = ConsoleProgress(
                enabled = !noProgress && System.console() != null,
            )

            // ── Resolve output ─────────────────────────────────────────
            val resolvedOutput = output.ifBlank {
                System.getenv("SCOOP_AI_OUTPUT") ?: ""
            }

            progress.start()
            val result: ManifestResult = try {
                runBlocking {
                    val generator = compositionRoot.createGenerator(
                        onPhase = { name -> progress.phase(name) },
                    )
                    // Reset tool call budget for a fresh run
                    DefaultToolRegistryFactory.getToolBudget()?.reset()
                    generator.generate(
                        url = envArgs.url,
                        outputPath = resolvedOutput.ifBlank { null },
                        userPrompt = effectiveUserPrompt,
                    )
                }
            } finally {
                progress.done()
            }

            // ── Handle result ──────────────────────────────────────────
            val manifest = result.manifest
            if (result.noAssets) {
                throw NoAssetsError("No Windows assets found for the given project.")
            }
            if (manifest.isBlank()) {
                throw GeneralError("no manifest generated")
            }

            // Print suggested app name when available
            if (result.suggestedAppName != null) {
                echo("Suggested app name: ${result.suggestedAppName}")
            }

            // Write manifest to file
            val appNameForOutput = result.suggestedAppName?.ifBlank { null }
                ?: compositionRoot.resolvedAppName
            val writePath = output.ifBlank { "bucket/$appNameForOutput.json" }
            compositionRoot.manifestIo.write(manifest, writePath)
            echo("Written to $writePath")
            val absoluteWritePath = java.io.File(writePath).absoluteFile.canonicalPath
            echo("Absolute path: $absoluteWritePath")

            // ── Print token usage ──────────────────────────────────────
            result.tokenUsage?.let { usage ->
                echo("Token usage: input=${usage.totalInputTokens} output=${usage.totalOutputTokens} total=${usage.totalTokens}")
            }
            if (result.durationMs > 0) {
                val seconds = result.durationMs / 1000
                val minutes = seconds / 60
                val sec = seconds % 60
                echo("Duration: ${minutes}m ${sec}s (${result.durationMs}ms)")
            }

            // ── Finalize cache entries ─────────────────────────────────
            if (envArgs.keepCache) {
                val parsed = parseManifestMeta(manifest)
                logger.info { "[cache] Finalizing: app=$appNameForOutput version=${parsed.version} url=${parsed.url}" }
                if (parsed.version != null && parsed.url != null) {
                    val finalized = compositionRoot.cache.finalizeCacheEntry(
                        parsed.url, appNameForOutput, parsed.version
                    )
                    if (finalized != null) {
                        echo("Cache: ${finalized.toAbsolutePath()}")
                        echo("scoop install $appNameForOutput will reuse this file.")
                    } else {
                        logger.warn { "[cache] No cached file found for ${parsed.url}" }
                    }
                } else {
                    logger.warn { "[cache] Could not parse manifest: version=${parsed.version} url=${parsed.url}" }
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
     * Maps flag names consistently: `noCache` → `keepCache` (inverted), `persist` → `persistMode`, etc.
     */
    internal fun buildEnvArgs(): EnvArgs {
        // Normalize persist: "no" → "none" for backward compat with old EnvArgs
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
            keepCache = !noCache,
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
        /** Extract minimal manifest metadata from the JSON string. */
        private data class ManifestMeta(val version: String?, val url: String?)

        private fun parseManifestMeta(manifest: String): ManifestMeta {
            val version = Regex(""""version"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
            val url = Regex(""""url"\s*:\s*"(https?://[^"]+)"""").find(manifest)?.groupValues?.get(1)
            return ManifestMeta(version, url)
        }
    }
}
