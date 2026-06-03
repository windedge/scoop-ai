package com.scoopai.core.utils

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import com.scoopai.core.TokenAccumulator
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Safely truncate a string for logging, replacing non-printable and non-ASCII
 * characters to avoid garbled output on non-UTF-8 consoles.
 * Truncates to [maxLen] code points.
 */
internal fun sanitizeForLog(text: String, maxLen: Int = 120): String {
    val sanitized = text
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ')
        .replace(Regex("[^\\x20-\\x7E]"), "?")
    val codePoints = sanitized.codePoints().toArray()
    return if (codePoints.size <= maxLen) {
        sanitized
    } else {
        val truncated = codePoints.take(maxLen)
            .map { String(Character.toChars(it)) }
            .joinToString("")
        "$truncated..."
    }
}

/**
 * Logs a short summary of each LLM response: tool calls, text preview, reasoning preview.
 * Helps debug silent thinking periods and identify which phase is consuming tokens.
 */
internal fun logLlmResponse(response: Message.Assistant?, accumulator: TokenAccumulator) {
    val resp = response ?: return
    val toolCalls = resp.parts.filterIsInstance<MessagePart.Tool.Call>()
    val texts = resp.parts.filterIsInstance<MessagePart.Text>()
    val reasoning = resp.parts.filterIsInstance<MessagePart.Reasoning>()
    val finishReason = resp.finishReason

    val totalIn = accumulator.input
    val totalOut = accumulator.output

    val parts = buildList {
        if (toolCalls.isNotEmpty()) {
            val callDescs = toolCalls.joinToString(", ") { tc ->
                val argPreview = tc.args.take(80).replace("\n", " ")
                "${tc.tool}($argPreview)"
            }
            add("toolCalls=[$callDescs]")
        }
        if (texts.isNotEmpty()) {
            val combined = texts.joinToString(" ") { it.text }.replace("\n", " ")
            val preview = if (combined.length > 300) combined.substring(0, 300) + "...(${combined.length}c)" else combined
            add("text=[$preview]")
        }
        if (reasoning.isNotEmpty()) {
            val combined = reasoning.joinToString(" ") { r -> r.content.joinToString(" ") }.replace("\n", " ")
            val preview = if (combined.length > 200) combined.substring(0, 200) + "...(${combined.length}c)" else combined
            add("reasoning=[$preview]")
        }
        add("finish=$finishReason")
    }
    logger.info { "[llm] in=${resp.metaInfo.inputTokensCount} out=${resp.metaInfo.outputTokensCount} cumIn=$totalIn cumOut=$totalOut | ${parts.joinToString(" ")}" }
}
