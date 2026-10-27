package com.scoopai.core

/**
 * Aggregate of the engine's event callbacks that always travel together
 * (ConsoleProgress onPhase, checkpoint/phase-output listeners, host tool
 * activity). Replaces the per-parameter callback relay through
 * DefaultScoopAiFactory -> ScoopManifestGenerator -> strategy.
 *
 * Created via [SessionCallbacks] builder. A missing callback is simply null;
 * [empty] is the no-feedback default.
 */
class SessionCallbacks(
    /** Invoked when a phase starts (name: "research", "analyze", "generate", "assist", "repair", "verify"). */
    val onPhase: ((String) -> Unit)? = null,
    /**
     * Invoked once per completed phase with its serialized output JSON
     * (`phaseName, output`). Un-masked content: intended for in-memory
     * checkpoints and artifacts writers only.
     */
    val onPhaseOutput: ((phaseName: String, output: String) -> Unit)? = null,
    /** Real-time tool call activity (Started / Completed / Failed). */
    val onToolActivity: ((ToolActivity) -> Unit)? = null,
) {
    companion object {
        /** Aggregate with every callback unset. */
        val empty: SessionCallbacks = SessionCallbacks()
    }

    /**
     * Builder for [SessionCallbacks]; late registration lets hosts wire
     * callbacks after deciding on the session shape.
     */
    class Builder {
        var onPhase: ((String) -> Unit)? = null
            private set
        var onPhaseOutput: ((phaseName: String, output: String) -> Unit)? = null
            private set
        var onToolActivity: ((ToolActivity) -> Unit)? = null
            private set

        fun onPhase(callback: ((String) -> Unit)?) {
            onPhase = callback
        }

        fun onPhaseOutput(callback: ((phaseName: String, output: String) -> Unit)?) {
            onPhaseOutput = callback
        }

        fun onToolActivity(callback: ((ToolActivity) -> Unit)?) {
            onToolActivity = callback
        }

        fun build(): SessionCallbacks = SessionCallbacks(onPhase, onPhaseOutput, onToolActivity)
    }
}

/** Builder entry point for [SessionCallbacks]. */
fun sessionCallbacks(builder: SessionCallbacks.Builder.() -> Unit): SessionCallbacks =
    SessionCallbacks.Builder().apply(builder).build()


/**
 * Host capabilities consumed by the assist subgraph (Chat with AI). A host
 * that does not provide a capability leaves it null, and the corresponding
 * tool is not registered and the prompts omit its guidance.
 *
 * @property saveToBucket Backs the `save_to_bucket` tool:
 *   (bucket, name, overwrite) -> result JSON.
 * @property installApp Backs the `install_app` tool:
 *   (bucket, name) -> result JSON; a null bucket direct-installs the current
 *   manifest from its local file.
 * @property readManifestRef Backs the `read_manifest_reference` tool:
 *   (bucket, name) -> result JSON, for the `@bucket/app` reference syntax.
 */
class AssistHost(
    val saveToBucket: (suspend (bucket: String?, name: String?, overwrite: Boolean) -> String)? = null,
    val installApp: (suspend (bucket: String?, name: String) -> String)? = null,
    val readManifestRef: (suspend (bucket: String, name: String) -> String)? = null,
) {
    companion object {
        /** Host with no capabilities: every assist host tool stays unregistered. */
        val NoOp: AssistHost = AssistHost()
    }
}
