package com.scoopai.tools

/**
 * Library-facing configuration for [DefaultScoopAiFactory.createGenerator].
 *
 * Defaults target GUI / embedded hosts (`nonInteractive=true`, `verify=false`).
 * CLI maps EnvArgs onto this type before assembly.
 */
data class ScoopAiConfig(
    val provider: String,
    val apiKey: String,
    val baseUrl: String? = null,
    val model: String? = null,
    val githubToken: String? = null,
    val appName: String? = null,
    val persistMode: String = "auto",
    val keepCache: Boolean = true,
    val verify: Boolean = false,
    val nonInteractive: Boolean = true,
    val maxIterations: Int = 500,
    val skipDownloadConfirm: Boolean = false,
)
