package com.scoopai.cli

/**
 * Resolved CLI arguments.
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
 * @property outputFormat Output format: "text" (default) or "json".
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
    /** Output format: "text" or "json". */
    val outputFormat: String = "text",
)
