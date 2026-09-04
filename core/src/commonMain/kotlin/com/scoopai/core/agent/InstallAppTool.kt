package com.scoopai.core.agent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * Arguments of the `install_app` tool.
 *
 * Unlike `save_to_bucket`, both parameters are required with no defaults: the
 * model installs a manifest that was already saved under a concrete bucket and
 * name, so there is nothing sensible to default to. When the manifest has not
 * been saved yet the prompt tells the model to save first.
 */
@Serializable
data class InstallAppArgs(
    val bucket: String,
    val name: String,
)

/**
 * Exposes the host's app-install capability as a SimpleTool named `install_app`.
 *
 * Architecture A (host callback): the engine owns no install logic. Execution
 * forwards bucket and name to the [installHandler] suspend callback provided by
 * the host (e.g. Scooper) and returns the host's JSON result verbatim, so the
 * model can honestly report the outcome (including an already-installed app).
 *
 * Only registered when the host provides a handler; CLI sessions never see it.
 */
class InstallAppTool(
    private val installHandler: suspend (bucket: String, name: String) -> String,
) : SimpleTool<InstallAppArgs>(
    argsType = typeToken<InstallAppArgs>(),
    name = TOOL_NAME,
    description = "Install an app from its already-saved Scoop bucket manifest and report the outcome. " +
        "Both bucket and name are required: use the bucket and name the manifest was saved under " +
        "(save it first with save_to_bucket if needed). Returns JSON with success and either app/bucket " +
        "or an error; an already-installed app is reported via message.",
) {
    override suspend fun execute(args: InstallAppArgs): String = installHandler(args.bucket, args.name)

    companion object {
        /** Koog schema name exposed to the LLM (deliberately snake_case). */
        const val TOOL_NAME = "install_app"
    }
}
