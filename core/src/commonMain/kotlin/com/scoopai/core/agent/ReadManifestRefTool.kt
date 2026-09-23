package com.scoopai.core.agent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * Arguments of the `read_manifest_reference` tool.
 *
 * Both parameters are required: `bucket` selects the local Scoop bucket and
 * `name` the app manifest inside it. The `@bucket/app` token in the user's
 * message maps directly to these two arguments (bucket/app).
 */
@Serializable
data class ReadManifestRefArgs(
    val bucket: String,
    val name: String,
)

/**
 * Exposes the host's local-manifest-read capability as a SimpleTool named
 * `read_manifest_reference`.
 *
 * Architecture A (host callback): the engine owns no local-manifest lookup.
 * Execution normalizes both arguments (trim; a blank argument yields an error
 * JSON instead of calling the handler) and forwards bucket and name to the
 * [readHandler] suspend callback provided by the host (e.g. Scooper), returning
 * the host's JSON result verbatim, so the model can honestly relay success or
 * failure (including a missing manifest). The model uses the returned manifest
 * content as a base for edits or as a style reference.
 *
 * Only registered when the host provides a handler; CLI sessions never see it.
 */
class ReadManifestRefTool(
    private val readHandler: suspend (bucket: String, name: String) -> String,
) : SimpleTool<ReadManifestRefArgs>(
    argsType = typeToken<ReadManifestRefArgs>(),
    name = TOOL_NAME,
    description = "Read the raw JSON of an existing manifest from a local Scoop bucket. " +
        "Pass bucket and name exactly as written in the user's @bucket/app reference " +
        "(e.g. @main/golang means bucket=main, name=golang). " +
        "Returns JSON with the manifest content plus its bucket/name/path; " +
        "on failure returns {\"success\":false,\"error\":\"...\"} without throwing, " +
        "so you can tell the user the reference could not be read.",
) {
    override suspend fun execute(args: ReadManifestRefArgs): String {
        val bucket = args.bucket.trim()
        val name = args.name.trim()
        if (bucket.isEmpty() || name.isEmpty()) {
            // Mirror the install_app failure-JSON philosophy: a blank argument is
            // a caller error the model must see, never an exception crossing the
            // tool boundary.
            return """{"success":false,"error":"Both bucket and name are required and must not be blank."}"""
        }
        return readHandler(bucket, name)
    }

    companion object {
        /** Koog schema name exposed to the LLM (deliberately snake_case). */
        const val TOOL_NAME = "read_manifest_reference"
    }
}
