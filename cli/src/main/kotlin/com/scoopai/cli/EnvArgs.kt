package com.scoopai.cli

import com.scoopai.cli.error.UsageError

/**
 * Parsed and resolved CLI arguments.
 *
 * This is a pure data class: it only carries values, no env var resolution.
 * All fields have sensible defaults so tests can construct partial instances.
 *
 * @property url GitHub repository URL or project URL.
 * @property output Output file path (default: "" = auto-detect).
 * @property provider LLM provider name (default: "" = auto-detect from env).
 * @property apiKey API key (default: "" = auto-detect from env).
 * @property baseUrl Custom API base URL.
 * @property modelName LLM model name override.
 * @property verify Whether to run `scoop install` verification.
 * @property appName App name override for cache/output.
 * @property keepCache Whether to keep downloaded files in Scoop cache.
 * @property userPrompt Additional user requirements for manifest generation.
 * @property nonInteractive Skip interactive prompts.
 * @property persistMode Persist field policy: auto, force, none.
 * @property githubToken GitHub personal access token.
 * @property debug Enable debug output (stack traces).
 * @property noProgress Disable progress overlay.
 */
data class EnvArgs(
    val url: String,
    val output: String = "",
    val provider: String = "",
    val apiKey: String = "",
    val baseUrl: String? = null,
    val modelName: String? = null,
    val verify: Boolean = false,
    val appName: String? = null,
    val keepCache: Boolean = true,
    val userPrompt: String? = null,
    val nonInteractive: Boolean = false,
    val persistMode: String = "auto",
    val githubToken: String? = null,
    val debug: Boolean = false,
    val noProgress: Boolean = false,
) {
    companion object {
        /**
         * Parse CLI arguments into an [EnvArgs] instance.
         *
         * This is a pure function: it does not read environment variables or
         * perform any I/O. All flag values are captured as-is from [args].
         *
         * @throws UsageError if required arguments are missing or values are invalid.
         */
        fun parse(args: Array<String>): EnvArgs {
            var url = ""
            var output = ""
            var provider = ""
            var apiKey = ""
            var baseUrl: String? = null
            var modelName: String? = null
            var verify = false
            var appName: String? = null
            var keepCache = true
            var userPrompt: String? = null
            var nonInteractive = false
            var persistMode = "auto"
            var githubToken: String? = null
            var debug = false
            var noProgress = false

            val i = args.iterator()
            while (i.hasNext()) {
                when (val arg = i.next()) {
                    "-o", "--output" -> output = i.next()
                    "-P", "--provider" -> provider = i.next()
                    "--api-key" -> apiKey = i.next()
                    "--base-url" -> baseUrl = i.next()
                    "-m", "--model" -> modelName = i.next()
                    "-V", "--verify" -> verify = true
                    "--app-name" -> appName = i.next()
                    "--no-cache" -> keepCache = false
                    "-p", "--prompt" -> userPrompt = i.next()
                    "-n", "--non-interactive" -> nonInteractive = true
                    "--github-token" -> githubToken = i.next()
                    "--debug" -> debug = true
                    "--no-progress" -> noProgress = true
                    "-s", "--persist" -> {
                        val mode = i.next().lowercase()
                        persistMode = when (mode) {
                            "auto", "force", "none", "no" -> if (mode == "no") "none" else mode
                            else -> throw UsageError(
                                "--persist must be auto, force, none, or no (got: $mode)"
                            )
                        }
                    }
                    "-h", "--help" -> {
                        // --help is handled by the caller before parse();
                        // if we reach here just skip it
                    }
                    else -> {
                        if (url.isBlank()) {
                            url = arg
                        } else {
                            throw UsageError("Unexpected argument: $arg")
                        }
                    }
                }
            }

            if (url.isBlank()) {
                throw UsageError("Missing required argument: <url>")
            }

            return EnvArgs(
                url = url,
                output = output,
                provider = provider,
                apiKey = apiKey,
                baseUrl = baseUrl,
                modelName = modelName,
                verify = verify,
                appName = appName,
                keepCache = keepCache,
                userPrompt = userPrompt,
                nonInteractive = nonInteractive,
                persistMode = persistMode,
                githubToken = githubToken,
                debug = debug,
                noProgress = noProgress,
            )
        }
    }
}
