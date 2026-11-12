package com.scoopai.tools.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val debugJson = Json { ignoreUnknownKeys = true; prettyPrint = false }

/**
 * Logs the full request body of every POST/SSE call.
 * Controlled by `SCOOP_AI_DEBUG_HTTP` environment variable.
 *
 * Returns the input unchanged (this is a side-effect interceptor).
 */
public fun logRequestBody(rawJson: String): String {
    val displayBody = try {
        val parsed = debugJson.parseToJsonElement(rawJson)
        val pretty = Json { prettyPrint = true }
        pretty.encodeToString(JsonElement.serializer(), parsed)
    } catch (_: Exception) {
        rawJson
    }

    val separator = "═".repeat(60)
    println(buildString {
        appendLine(separator)
        appendLine("Request Body:")
        appendLine(displayBody)
        appendLine(separator)
    })

    return rawJson
}

/**
 * Returns `true` if `SCOOP_AI_DEBUG_HTTP` env var is set and not false/0.
 */
public fun isDebugHttpEnabled(): Boolean {
    val env = System.getenv("SCOOP_AI_DEBUG_HTTP") ?: return false
    return env.isNotBlank() && !env.equals("false", ignoreCase = true) && env != "0"
}
