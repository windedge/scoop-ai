package com.scoopai.tools.config

/**
 * Backward-compatible facade over [resolveProviderConfig].
 *
 * The resolution logic lives in [resolveProviderConfig]; this object keeps the
 * former call signature for existing callers and pins the engine default
 * provider constant ([DEFAULT_PROVIDER]). Kept while hosts still reference it.
 *
 * @see resolveProviderConfig
 */
@Deprecated("Use resolveProviderConfig instead", ReplaceWith("resolveProviderConfig(flagProvider = cliProvider, flagApiKey = cliApiKey, flagBaseUrl = cliBaseUrl, flagModel = cliModel)"))
object ProviderConfigResolver {

    /**
     * Resolves a complete [ResolvedProviderConfig] from CLI arguments
     * and environment variable fallbacks.
     */
    fun resolve(
        cliProvider: String = "",
        cliApiKey: String = "",
        cliBaseUrl: String? = null,
        cliModel: String? = null,
    ): ResolvedProviderConfig = resolveProviderConfig(
        flagProvider = cliProvider,
        flagApiKey = cliApiKey,
        flagBaseUrl = cliBaseUrl,
        flagModel = cliModel,
    )
}
