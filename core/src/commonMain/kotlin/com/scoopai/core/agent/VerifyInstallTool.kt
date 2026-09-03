package com.scoopai.core.agent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.scoopai.core.tools.api.ScoopVerifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Arguments of the `verify_install` tool.
 *
 * `reason` is a soft gate: it forces the model to state why it is running a real
 * install test before doing so. The value is not interpreted by the tool.
 */
@Serializable
data class VerifyInstallArgs(val reason: String = "")

/**
 * Exposes [ScoopVerifier] as a SimpleTool named `verify_install`. Executing it
 * performs a real `scoop install` of the manifest currently being edited or
 * reviewed (plus uninstall/persist cleanup) and returns the outcome as JSON.
 *
 * The manifest is resolved through [manifestProvider] at execution time because
 * the tool instance is created once per runSession while the manifest under edit
 * may change between review rounds.
 */
class VerifyInstallTool(
    private val verifier: ScoopVerifier,
    private val manifestProvider: () -> String,
) : SimpleTool<VerifyInstallArgs>(
    argsType = typeToken<VerifyInstallArgs>(),
    name = TOOL_NAME,
    description = "Run a real install test of the manifest under review: scoop install followed by " +
        "uninstall and persist cleanup. Returns JSON with success, app_name, exit_code and output. " +
        "Use it only when the manifest structure looks suspicious and JSON review alone is not enough. " +
        "The optional reason parameter must briefly state why this manifest needs a real install test.",
) {
    // No custom descriptor override: SimpleTool auto-generates the schema from
    // VerifyInstallArgs, whose defaulted `reason` decodes as an optional parameter.
    override suspend fun execute(args: VerifyInstallArgs): String {
        val manifest = manifestProvider()
        if (manifest.isBlank()) {
            return errorJson("No manifest is currently under review; cannot run an install test.")
        }
        return truncateOutput(verifier.verifyInstall(manifestJson = manifest, appName = VERIFY_APP_NAME))
    }

    /**
     * Caps the verifier JSON output before it reaches the LLM (full install logs
     * can reach 256KB). Truncates only the `output` field, keeping its head and
     * tail so the critical trailing error line survives; top-level fields are
     * never dropped and the whole payload stays under [OUTPUT_CAP_CHARS].
     */
    private fun truncateOutput(raw: String): String {
        if (raw.length <= OUTPUT_CAP_CHARS) return raw
        val snippet: (String) -> String = {
            it.take(HEAD_SNIPPET_CHARS) + TRUNCATION_MARKER + it.takeLast(TAIL_SNIPPET_CHARS)
        }
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val out = (obj["output"] as? JsonPrimitive)?.content ?: ""
            buildJsonObject {
                obj.forEach { (key, value) ->
                    put(key, if (key == "output") JsonPrimitive(snippet(out)) else value)
                }
            }.toString()
        } catch (_: Exception) {
            // Unparseable verifier output: fall back to a plain head+tail snippet.
            snippet(raw)
        }
    }

    private fun errorJson(error: String): String = buildJsonObject {
        put("success", false)
        put("app_name", VERIFY_APP_NAME)
        put("error", error)
    }.toString()

    companion object {
        /** Koog schema name exposed to the LLM (deliberately snake_case). */
        const val TOOL_NAME = "verify_install"

        /** Fixed test app name: independent of suggestedAppName and matches the
         * verifier whitelist ^[A-Za-z0-9._-]+$, so serial sessions cannot collide
         * with real bucket apps. */
        const val VERIFY_APP_NAME = "scoop-ai-verify"

        /** Max characters of the whole tool JSON result forwarded to the LLM. */
        const val OUTPUT_CAP_CHARS = 8 * 1024

        /** Characters kept from the head and tail of an oversized `output`. */
        const val HEAD_SNIPPET_CHARS = 2 * 1024
        const val TAIL_SNIPPET_CHARS = 2 * 1024

        private const val TRUNCATION_MARKER = "\n... (truncated) ...\n"
    }
}
