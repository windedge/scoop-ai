package com.scoopai.core.agent

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.SubgraphWithTaskUtils
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONLiteral
import ai.koog.serialization.JSONNull
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * Final artifact carrier for the generate/refine subgraphs: the manifest JSON
 * plus an optional natural-language explanation (architecture proposal 4.2).
 *
 * The explanation is the AI's conversational reply for the user instruction;
 * it is optional so old-format calls that only pass `result` keep working and
 * decode to [explanation] = null.
 */
@Serializable
public data class FinalizePayload(
    val manifest: String,
    val explanation: String? = null,
)

/**
 * Drop-in replacement for Koog's `FinishTool<String>` used by the generate/refine
 * subgraphs (whose `Output` is now [FinalizePayload]).
 *
 * ## Why this exists
 *
 * Koog 1.0.0's `FinishTool.decodeOutput` routes the `result` field through
 * `KotlinxDelegateSerializer` -> `Json.decodeFromJsonElement(String.serializer(), ...)`.
 * When the model passes a JSON **object** as `result` (a very common LLM behavior,
 * despite the prompt asking for a stringified manifest), kotlinx.serialization's
 * tree decoder does not fail with a clear type error. Instead `TaggedDecoder.popTag()`
 * underflows and throws:
 *
 * ```
 * java.lang.IndexOutOfBoundsException: Index -1 out of bounds for length 0
 * ```
 *
 * `executeFinishTool` catches it and reports
 * `"Failed to execute 'finalize_task_result' with error: Index -1 out of bounds for length 0"`,
 * which the model cannot act upon, burning every retry attempt on malformed
 * re-encodings. research/analyze phases are unaffected because their `Output` types
 * are data classes (object -> object decoding works).
 *
 * ## Two-parameter protocol
 *
 * The tool implements `finalize_task_result(result, explanation?)`:
 * - `result` (required): the manifest. Accepted in any of these forms and always
 *   decoded to a [String]:
 *   - JSON string: returned verbatim (the intended form);
 *   - JSON object / array: serialized back to compact JSON text (the manifest is
 *     recovered as-is, the model does not have to re-escape anything);
 *   - number / boolean literal: its literal text;
 *   - null / missing: empty string.
 * - `explanation` (optional): a short natural-language explanation of what was
 *   generated/changed and why, in the user's language. Missing or null decodes
 *   to a null [FinalizePayload.explanation]; the application layer then synthesizes
 *   a deterministic reply.
 *
 * The optional second parameter (instead of a `{"manifest": ..., "explanation": ...}`
 * wrapper) keeps the `result` contract unchanged and never lets the explanation
 * leak into the manifest encoding path (architecture proposal 4.2).
 *
 * The tool name stays `finalize_task_result` so prompts and the strategy graph
 * need no changes.
 */
@OptIn(InternalAgentToolsApi::class)
public class LenientStringFinishTool : Tool<FinalizePayload, FinalizePayload>(
    argsType = typeToken<FinalizePayload>(),
    resultType = typeToken<FinalizePayload>(),
    descriptor = ToolDescriptor(
        name = SubgraphWithTaskUtils.FINALIZE_SUBGRAPH_TOOL_NAME,
        description = "Call this tool when finish and provide final result. " +
            "The result parameter accepts the manifest JSON either as a string " +
            "(preferred) or as a JSON object (it will be converted to a JSON string).",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "result",
                description = "The final result text. Prefer passing the JSON manifest as a single string.",
                type = ToolParameterType.String,
            )
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                name = "explanation",
                description = "Optional. A short natural-language explanation of what was generated/changed " +
                    "and why, in the same language as the user's input.",
                type = ToolParameterType.String,
            )
        ),
    ),
) {
    override fun decodeArgs(rawArgs: JSONObject, serializer: JSONSerializer): FinalizePayload =
        extractPayload(rawArgs)

    override fun encodeArgs(args: FinalizePayload, serializer: JSONSerializer): JSONObject =
        wrapPayload(args)

    override fun decodeResult(rawResult: JSONElement, serializer: JSONSerializer): FinalizePayload =
        // The result delivered by Koog's toSafeResult may be a JSON string
        // literal, null, number, or array, not only the object form produced
        // by encodeResult. A blind `as JSONObject` cast would throw CCE, so we
        // dispatch safely instead (see extractPayload).
        extractPayload(rawResult)

    override fun encodeResult(result: FinalizePayload, serializer: JSONSerializer): JSONElement =
        wrapPayload(result)

    override suspend fun execute(args: FinalizePayload): FinalizePayload = args

    private fun wrapPayload(payload: FinalizePayload): JSONObject {
        val entries = LinkedHashMap<String, JSONElement>()
        entries["result"] = JSONLiteral(payload.manifest, isString = true)
        payload.explanation?.let { entries["explanation"] = JSONLiteral(it, isString = true) }
        return JSONObject(entries)
    }

    private companion object {
        /**
         * Reduces a raw element to its [FinalizePayload] form without unsafe casts:
         * - [JSONObject] is the `{"result": ..., "explanation": ...}` container
         *   produced by both [decodeArgs] and [encodeResult]; its entries are
         *   extracted (`result` leniently, `explanation` optionally);
         * - any other element ([JSONLiteral], [JSONNull], array, number) is
         *   treated as the manifest value itself, so [decodeResult] never throws
         *   CCE (or the historical IndexOutOfBoundsException) when Koog hands it
         *   a non-container element.
         */
        fun extractPayload(raw: JSONElement): FinalizePayload {
            if (raw !is JSONObject) {
                return FinalizePayload(manifest = extractResultValue(raw), explanation = null)
            }
            return FinalizePayload(
                manifest = extractResultValue(raw.entries["result"]),
                explanation = extractOptionalText(raw.entries["explanation"]),
            )
        }

        fun extractResultValue(value: JSONElement?): String = when (value) {
            null -> ""
            is JSONNull -> ""
            is JSONLiteral -> value.content
            // Object/array form: stringify to compact JSON text.
            else -> value.toKotlinxJsonElement().toString()
        }

        /** Missing / JSON null / anything non-textual decodes to null, never to an empty string.
         *  Number/boolean literals and object/array forms also decode to null: explanation is
         *  natural-language text, and stringified non-text values would overwrite a valid
         *  explanation from an earlier round. */
        fun extractOptionalText(value: JSONElement?): String? = when (value) {
            null, is JSONNull -> null
            is JSONLiteral -> value.content.takeIf { value.isString && it.isNotBlank() }
            else -> null
        }
    }
}
