package com.scoopai.tools.interaction

import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.toKoogJSONObject
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lenient argument normalization for `__ask_user__`.
 *
 * Models often send shapes that the strict `Args` serializer rejects with
 * "unknown key" errors: flat `{"question"|"prompt"|"message": "..."}`,
 * `questions` as object/string, nested `question` instead of `prompt`,
 * option objects keyed by `name`/`choice`, or invented keys such as
 * `isMultiSelect`. Everything is canonicalized to the structured
 * `{"questions": [...]}` format; unrecognized input still fails closed.
 */
internal fun canonicalizeAskUserArgs(raw: JSONObject): JSONObject {
    val input = raw.toKotlinxJsonObject()
    val questions = input["questions"]
    if (questions != null) {
        return buildJsonObject { put("questions", normalizeQuestions(questions)) }.toKoogJSONObject()
    }
    val promptText = firstText(input, "question", "message", "prompt")
        ?: return handleNoPromptText(input)
    return buildJsonObject {
        put("questions", buildJsonArray {
            add(buildJsonObject {
                put("prompt", promptText)
                input["options"]?.let { put("options", normalizeOptionList(it)) }
                input["allowOther"]?.let { put("allowOther", it) }
            })
        })
    }.toKoogJSONObject()
}

private val KNOWN_ROOT_KEYS =
    setOf("questions", "question", "message", "prompt", "options", "allowOther")

private fun handleNoPromptText(input: JsonObject): JSONObject {
    val unknownKeys = input.keys.filter { it !in KNOWN_ROOT_KEYS }
    if (input.isNotEmpty() && unknownKeys.size == input.keys.size) {
        throw IllegalArgumentException(
            "Unrecognized __ask_user__ argument keys: $unknownKeys; " +
                "expected a 'questions' array or a flat 'question' string"
        )
    }
    // Empty object, or only known auxiliary keys: decode to defaults,
    // execute() then answers with DEFAULT_RESPONSE.
    return input.toKoogJSONObject()
}

private fun normalizeQuestions(element: JsonElement): JsonArray = when (element) {
    is JsonArray -> buildJsonArray { element.forEach { add(normalizeQuestion(it)) } }
    is JsonObject -> buildJsonArray { add(normalizeQuestion(element)) }
    is JsonNull -> buildJsonArray { }
    is JsonPrimitive -> buildJsonArray { add(singleQuestion(element.content)) }
}

private fun normalizeQuestion(element: JsonElement): JsonObject = when (element) {
    is JsonObject -> {
        val prompt = firstText(element, "prompt", "question", "text")
            ?: throw IllegalArgumentException(
                "__ask_user__ question element has no prompt text: $element"
            )
        buildJsonObject {
            put("prompt", prompt)
            element["id"].takeString()?.let { put("id", it) }
            element["label"].takeString()?.let { put("label", it) }
            element["options"]?.let { put("options", normalizeOptionList(it)) }
            (element["allowOther"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let {
                put("allowOther", it)
            }
        }
    }
    else -> throw IllegalArgumentException(
        "__ask_user__ question element must be an object: $element"
    )
}

private fun singleQuestion(prompt: String): JsonObject =
    buildJsonObject { put("prompt", prompt) }

private fun normalizeOptionList(element: JsonElement): JsonArray = when (element) {
    is JsonNull -> buildJsonArray { }
    is JsonArray -> buildJsonArray { element.forEach { add(normalizeOption(it)) } }
    else -> throw IllegalArgumentException("__ask_user__ options must be an array: $element")
}

private fun normalizeOption(element: JsonElement): JsonObject = when (element) {
    is JsonPrimitive -> buildJsonObject { put("label", element.content) }
    is JsonObject -> {
        val label = firstText(element, "label", "name", "option", "choice", "value")
            ?: throw IllegalArgumentException(
                "__ask_user__ option element has no label: $element"
            )
        buildJsonObject {
            put("label", label)
            element["value"].takeString()?.takeIf { it != label }?.let { put("value", it) }
            firstText(element, "description", "desc")?.let { put("description", it) }
        }
    }
    else -> throw IllegalArgumentException(
        "__ask_user__ option element must be a string or object: $element"
    )
}

private fun JsonElement?.takeString(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.ifBlank { null }

private fun firstText(obj: JsonObject, vararg keys: String): String? =
    keys.firstNotNullOfOrNull { obj[it].takeString() }
