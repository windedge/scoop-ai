package com.scoopai.cli

import com.scoopai.core.ManifestResult
import kotlinx.serialization.json.Json

/**
 * Formats a [ManifestResult] as a pretty-printed JSON string.
 *
 * The output conforms to the CLI output contract with fields:
 * - manifest / suggestedAppName / noAssets / tokenUsage / durationMs / stages
 * - Each stage has: phaseName / durationMs / inputTokens / outputTokens / toolCallCount
 *
 * The output always ends with a trailing newline for pipe-friendly consumption.
 */
object JsonFormatter {

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    /**
     * Serialize [result] to a pretty-printed JSON string with a trailing newline.
     */
    fun format(result: ManifestResult): String {
        return json.encodeToString(ManifestResult.serializer(), result) + "\n"
    }
}
