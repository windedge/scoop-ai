package com.scoopai.core.utils

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
