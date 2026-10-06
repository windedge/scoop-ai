package com.scoopai.core.agent

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.SubgraphWithTaskUtils
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONLiteral
import ai.koog.serialization.JSONArray
import ai.koog.serialization.JSONNull
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * Final artifact carrier for the generate/assist/repair subgraphs: the manifest JSON
 * plus an optional natural-language explanation, an optional escape-hatch flag
 * (architecture proposal 4.2 + answer escape hatch), and an optional list of
 * phase activities (what changed and why, for host-side step summaries).
 *
 * The explanation is the AI's conversational reply for the user instruction;
 * it is optional so old-format calls that only pass `result` keep working and
 * decode to [explanation] = null.
 *
 * The optional [answered] flag is true only when the user message was a pure
 * question about the manifest (no change request); see the escape hatch in
 * [com.scoopai.core.agent.prompt.buildAssistPrompt]. Old two-parameter encodings
 * that omit `answered` decode to null (backward compatible).
 *
 * The optional [activities] list carries 3-6 short sentences naming each concrete
 * transformation applied to the manifest and why. Encodings without it decode to
 * null (backward compatible); it never enters the manifest itself.
 */
@Serializable
public data class FinalizePayload(
    val manifest: String,
    val explanation: String? = null,
    val answered: Boolean? = null,
    val activities: List<String>? = null,
)

/**
 * Drop-in replacement for Koog's `FinishTool<String>` used by the generated
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
 * ## Three-parameter protocol
 *
 * The tool implements `finalize_task_result(result, explanation?, answered?)`:
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
 * - `answered` (optional): true only when the user message is a pure question and
 *   the manifest is returned verbatim. Only a JSON boolean literal is accepted;
 *   object/array/string/number/null all decode to null (symmetric with
 *   [FinalizePayload.explanation] tolerance, so a malformed value cannot silently
 *   switch a change request into a pure-answer escape).
 * - `activities` (optional): a JSON array of short sentences, each naming one
 *   concrete transformation applied to the manifest and why. Only an array whose
 *   elements are all string literals is accepted; string/object/array-with-
 *   non-strings/null/missing all decode to a null [FinalizePayload.activities]
 *   (symmetric with [extractOptionalText] tolerance).
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
            ),
            ToolParameterDescriptor(
                name = "answered",
                description = "Set true only when the user message is a pure question; keep result identical to the input manifest",
                type = ToolParameterType.Boolean,
            ),
            ToolParameterDescriptor(
                name = "activities",
                description = "Optional. 3-6 short sentences, each naming one concrete transformation applied to the manifest " +
                    "and why (e.g. Appended #/dl.7z for the NSIS installer, Added autoupdate.hash from checksums.txt).",
                type = ToolParameterType.List(ToolParameterType.String),
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
        payload.answered?.let { entries["answered"] = JSONLiteral(it.toString(), isString = false) }
        payload.activities?.let { activities ->
            entries["activities"] = JSONArray(activities.map { JSONLiteral(it, isString = true) })
        }
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
                answered = extractOptionalBool(raw.entries["answered"]),
                activities = extractOptionalStringList(raw.entries["activities"]),
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

        /** Only a JSON boolean literal (isString==false and content "true"/"false") decodes to a
         *  non-null Boolean. Missing / JSON null / object / array / string / number all decode
         *  to null so a malformed value cannot silently turn a change request into a pure-answer
         *  escape (symmetric with [extractOptionalText]). */
        fun extractOptionalBool(value: JSONElement?): Boolean? = when (value) {
            null, is JSONNull -> null
            is JSONLiteral -> when {
                value.isString -> null
                value.content == "true" -> true
                value.content == "false" -> false
                else -> null
            }
            else -> null
        }

        /** Only a JSON array whose elements are all string literals decodes to a List<String>.
         *  Missing / JSON null / string / object / array-with-non-string-elements all decode
         *  to null (symmetric with [extractOptionalText] tolerance, so a malformed value can
         *  never leak into the host's step summaries). */
        fun extractOptionalStringList(value: JSONElement?): List<String>? {
            if (value !is JSONArray) return null
            val items = value.elements.map { element ->
                (element as? JSONLiteral)?.takeIf { it.isString }?.content ?: return null
            }
            return items
        }
    }
}
