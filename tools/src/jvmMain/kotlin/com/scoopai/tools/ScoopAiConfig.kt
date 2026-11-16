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
    /**
     * UI language name (e.g. "Chinese") used as the fallback reply language
     * when the request itself carries no natural language. Read at
     * construction time; hosts must rebuild the generator per run to pick up
     * a locale switch. When null the prompt keeps the 0.1.16 single-sentence
     * wording. Manifest data fields stay English regardless.
     */
    val replyLanguage: String? = null,
)
