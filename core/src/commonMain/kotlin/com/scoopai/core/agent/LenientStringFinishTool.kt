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

/**
 * Drop-in replacement for Koog's `FinishTool<String>` used by the generate/refine
 * subgraphs (whose `Output` is the manifest JSON **string**).
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
 * ## Behavior
 *
 * `result` is accepted in any of these forms and always decoded to a [String]:
 * - JSON string: returned verbatim (the intended form);
 * - JSON object / array: serialized back to compact JSON text (the manifest is
 *   recovered as-is, the model does not have to re-escape anything);
 * - number / boolean literal: its literal text;
 * - null / missing: empty string.
 *
 * The tool name stays `finalize_task_result` so prompts and the strategy graph
 * need no changes.
 */
@OptIn(InternalAgentToolsApi::class)
public class LenientStringFinishTool : Tool<String, String>(
    argsType = typeToken<String>(),
    resultType = typeToken<String>(),
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
    ),
) {
    override fun decodeArgs(rawArgs: JSONObject, serializer: JSONSerializer): String =
        extractResultString(rawArgs)

    override fun encodeArgs(args: String, serializer: JSONSerializer): JSONObject =
        wrapResult(args)

    override fun decodeResult(rawResult: JSONElement, serializer: JSONSerializer): String =
        // The result delivered by Koog's toSafeResult may be a JSON string
        // literal, null, number, or array, not only the object form produced
        // by encodeResult. A blind `as JSONObject` cast would throw CCE, so we
        // dispatch safely instead (see extractResultString).
        extractResultString(rawResult)

    override fun encodeResult(result: String, serializer: JSONSerializer): JSONElement =
        wrapResult(result)

    override suspend fun execute(args: String): String = args

    private fun wrapResult(value: String): JSONObject =
        JSONObject(mapOf("result" to JSONLiteral(value, isString = true)))

    private companion object {
        /**
         * Reduces a raw result element to its [String] form without unsafe casts:
         * - [JSONObject] is the `{"result": ...}` container produced by both
         *   [decodeArgs] and [encodeResult]; its `result` entry is extracted;
         * - any other element ([JSONLiteral], [JSONNull], array, number) is
         *   treated as the value itself, so [decodeResult] never throws CCE
         *   when Koog hands it a non-container element.
         */
        fun extractResultString(raw: JSONElement): String =
            extractResultValue(if (raw is JSONObject) raw.entries["result"] else raw)

        fun extractResultValue(value: JSONElement?): String = when (value) {
            null -> ""
            is JSONNull -> ""
            is JSONLiteral -> value.content
            // Object/array form: stringify to compact JSON text.
            else -> value.toKotlinxJsonElement().toString()
        }
    }
}
