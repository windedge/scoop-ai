package com.scoopai.core.agent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * Arguments of the `save_to_bucket` tool.
 *
 * `bucket` and `name` are optional on purpose: null means the host applies its
 * own default rules (e.g. last-used bucket, then main, then the first bucket;
 * suggested app name). `overwrite` must be set only after the user confirmed
 * that an existing file may be replaced.
 */
@Serializable
data class SaveToBucketArgs(
    val bucket: String? = null,
    val name: String? = null,
    val overwrite: Boolean = false,
)

/**
 * Exposes the host's manifest-save capability as a SimpleTool named `save_to_bucket`.
 *
 * Architecture A (host callback): the engine owns no save logic. Execution
 * forwards the args to the [saveHandler] suspend callback provided by the host
 * (e.g. Scooper) and returns the host's JSON result verbatim, so the model can
 * honestly report the saved path or error.
 *
 * Only registered when the host provides a handler; CLI sessions never see it.
 */
class SaveToBucketTool(
    private val saveHandler: suspend (SaveToBucketArgs) -> String,
) : SimpleTool<SaveToBucketArgs>(
    argsType = typeToken<SaveToBucketArgs>(),
    name = TOOL_NAME,
    description = "Save the current manifest into a local Scoop bucket and get its install path. " +
        "Returns JSON with success, path and error. Both bucket and name are optional: omit them " +
        "to let the host apply its default rules. If the result reports the file already exists " +
        "(needsOverwrite=true), ask the user whether to overwrite, then call again with overwrite=true.",
) {
    // No custom descriptor override: SimpleTool auto-generates the schema from
    // SaveToBucketArgs, whose fields all have defaults and decode as optional.
    override suspend fun execute(args: SaveToBucketArgs): String = saveHandler(args)

    companion object {
        /** Koog schema name exposed to the LLM (deliberately snake_case). */
        const val TOOL_NAME = "save_to_bucket"
    }
}
