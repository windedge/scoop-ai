package com.scoopai.core.agent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * Arguments of the `install_app` tool.
 *
 * [bucket] is optional: passing a bucket installs the manifest previously
 * saved there (the classic path). Omitting it - or passing an empty string -
 * means "direct install": the host installs the current manifest from its
 * local file without saving it to any bucket. [name] stays required because
 * the host needs the app name to resolve the manifest file and report
 * install.json state.
 */
@Serializable
data class InstallAppArgs(
    val bucket: String? = null,
    val name: String,
)

/**
 * Exposes the host's app-install capability as a SimpleTool named `install_app`.
 *
 * Architecture A (host callback): the engine owns no install logic. Execution
 * normalizes [InstallAppArgs.bucket] via [normalizeInstallBucket] and forwards
 * bucket and name to the [installHandler] suspend callback provided by the host
 * (e.g. Scooper), returning the host's JSON result verbatim, so the model can
 * honestly report the outcome (including an already-installed app). A null
 * bucket tells the host to direct-install the current manifest from its local
 * file; a non-null bucket tells it to install from the saved bucket manifest.
 *
 * Only registered when the host provides a handler; CLI sessions never see it.
 */
class InstallAppTool(
    private val installHandler: suspend (bucket: String?, name: String) -> String,
) : SimpleTool<InstallAppArgs>(
    argsType = typeToken<InstallAppArgs>(),
    name = TOOL_NAME,
    description = "Install an app and report the outcome. Two paths: " +
        "(1) bucket install - pass both bucket and name to install the manifest previously saved " +
        "to that bucket with save_to_bucket; " +
        "(2) direct install - pass only name (omit bucket or pass an empty string) to install " +
        "the current manifest directly from its local file without saving it to any bucket; " +
        "a direct-installed app has no bucket affiliation and can be saved to a bucket later. " +
        "Use the direct path ONLY when the user asks to install right away without saving. " +
        "Returns JSON with success and either app/bucket or an error; an already-installed app " +
        "is reported via message.",
) {
    override suspend fun execute(args: InstallAppArgs): String =
        installHandler(normalizeInstallBucket(args.bucket), args.name)

    companion object {
        /** Koog schema name exposed to the LLM (deliberately snake_case). */
        const val TOOL_NAME = "install_app"

        /**
         * Normalizes the raw bucket argument: blank/whitespace input from the
         * model means "no bucket" (direct install) and becomes null, real
         * bucket names are trimmed.
         */
        internal fun normalizeInstallBucket(raw: String?): String? = raw?.trim()?.ifEmpty { null }
    }
}
