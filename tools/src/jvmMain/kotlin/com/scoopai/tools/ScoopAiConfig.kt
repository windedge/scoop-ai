package com.scoopai.tools

/**
 * Library-facing configuration for [DefaultScoopAiFactory.createGenerator].
 *
 * Defaults target GUI / embedded hosts (`nonInteractive=true`). Install
 * verification is always on and carries no user-facing switch; the verifier
 * itself stays optional at runtime (null when scoop is unavailable).
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
    val nonInteractive: Boolean = true,
    val maxIterations: Int = 500,
    val skipDownloadConfirm: Boolean = false,
)
