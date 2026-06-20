package com.scoopai.core.artifacts

/**
 * Wraps a [RunArtifacts] instance and applies a masker function
 * to all content before writing. This ensures sensitive information
 * (API keys, tokens, etc.) is redacted from debug artifacts.
 *
 * @param delegate The underlying [RunArtifacts] to write to.
 * @param masker Function that masks sensitive content in a string.
 */
class SensitiveArtifactWriter(
    val delegate: RunArtifacts,
    private val masker: (String) -> String,
) {
    /**
     * Write a phase's output, masking sensitive content first.
     */
    fun writePhase(phaseName: String, content: String) {
        delegate.writePhase(phaseName, masker(content))
    }

    /**
     * Mark the run as completed.
     */
    fun finalize() = delegate.finalize()

    /**
     * Mark the run as failed with masked error message.
     */
    fun markFailed(error: String) {
        delegate.markFailed(masker(error))
    }
}
