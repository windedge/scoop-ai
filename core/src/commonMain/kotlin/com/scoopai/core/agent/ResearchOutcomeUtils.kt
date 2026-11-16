package com.scoopai.core.agent

import kotlinx.serialization.json.Json
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** Shared Json instance for phase output parsing (lenient). */
internal val phaseJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

private const val NO_ASSETS_DETAIL_LIMIT = 140

/** Hard cap for the whole synthesized explanation, ellipsis included. */
private const val NO_ASSETS_EXPLANATION_LIMIT = 220

private const val NO_ASSETS_ELLIPSIS = "..."

/**
 * Extracts the first sentence of [text], truncated to [limit] characters.
 *
 * The excerpt ends at the first sentence boundary (a "." or full-width period
 * followed by whitespace, or the end of the text) found within the first [limit]
 * characters. Abbreviation periods such as the one in "e.g." (a single letter
 * between two periods) do not count as a boundary. When no boundary exists
 * within [limit], the excerpt is backed off to the last space so words are not
 * cut in half.
 */
internal fun firstSentence(text: String, limit: Int = NO_ASSETS_DETAIL_LIMIT): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    val window = trimmed.take(limit)
    window.forEachIndexed { index, c ->
        if (c != '.' && c != '\u3002') return@forEachIndexed
        val next = window.getOrNull(index + 1)
        if (next != null && !next.isWhitespace()) return@forEachIndexed
        // Skip abbreviation periods such as "e.g." / "i.e." (letter between two periods).
        if (c == '.' && index >= 2 && window[index - 2] == '.' && window[index - 1].isLetter()) return@forEachIndexed
        return window.take(index + 1)
    }
    // No sentence boundary within the limit: keep short text whole, otherwise
    // back off to the last space to avoid splitting a word.
    if (trimmed.length <= limit) return trimmed
    val lastSpace = window.lastIndexOf(' ')
    return if (lastSpace > 0) window.take(lastSpace) else window
}

/**
 * Deterministically synthesizes the noAssets explanation from the research output
 * (English fallback text, architecture proposal 4.7).
 *
 * The noAssets early-finish branch never reaches the generate/assist/repair subgraphs, so
 * the explanation of such a run can never come from a finalize_task_result call;
 * the engine produces it from the fields the research phase already gathered
 * ([ResearchOutput.alternatives], [ResearchOutput.notes], [ResearchOutput.project]).
 *
 * Output style is one or two short user-facing sentences: a fixed lead-in with the
 * project name, plus at most the first sentence of alternatives (preferred) or notes
 * (fallback). The remaining field content is not listed, and the total length is
 * capped at [NO_ASSETS_EXPLANATION_LIMIT] characters with an ellipsis on overflow.
 *
 * When the model provided [ResearchOutput.user_explanation], it takes precedence:
 * the trimmed text replaces the whole synthesized join and is capped the same way;
 * a blank value falls back to the deterministic English join unchanged.
 */
internal fun buildNoAssetsExplanation(research: ResearchOutput): String? {
    val userExplanation = research.user_explanation?.trim()
    if (!userExplanation.isNullOrBlank()) {
        // Same cap policy as the fallback join: head kept, trailing spaces
        // trimmed before the ellipsis.
        return if (userExplanation.length > NO_ASSETS_EXPLANATION_LIMIT) {
            userExplanation.take(NO_ASSETS_EXPLANATION_LIMIT - NO_ASSETS_ELLIPSIS.length).trimEnd() + NO_ASSETS_ELLIPSIS
        } else {
            userExplanation
        }
    }
    val builder = StringBuilder("No Windows assets found for this project")
    if (research.project.isNotBlank()) {
        builder.append(" (").append(research.project.trim()).append(")")
    }
    builder.append(".")
    // One short detail sentence at most: prefer alternatives, fall back to notes.
    val detailSource = when {
        research.alternatives.isNotBlank() -> research.alternatives
        research.notes.isNotBlank() -> research.notes
        else -> null
    }
    if (detailSource != null) {
        val detail = firstSentence(detailSource)
        if (detail.isNotBlank()) {
            builder.append(" ").append(detail)
        }
    }
    var explanation = builder.toString()
    if (explanation.length > NO_ASSETS_EXPLANATION_LIMIT) {
        explanation = explanation.take(NO_ASSETS_EXPLANATION_LIMIT - NO_ASSETS_ELLIPSIS.length).trimEnd() + NO_ASSETS_ELLIPSIS
    }
    return explanation
}
