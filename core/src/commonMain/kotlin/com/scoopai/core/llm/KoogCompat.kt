package com.scoopai.core.llm

import ai.koog.agents.core.tools.Tool
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import com.scoopai.core.agent.FinalizePayload
import com.scoopai.core.agent.LenientStringFinishTool

/**
 * Single facade over every runtime workaround we keep against Koog
 * (currently 1.1.1). Each entry point owns one workaround family and its
 * KDoc carries the trigger and retirement conditions, replacing the manual
 * ledger that used to live in AGENTS.md.
 *
 * Three entries:
 * - [decorateHttpClient]: assembles the request/response workaround chain
 *   ([stripToolChoice] + the three response rewriters) on top of the shared
 *   retry-decorated HTTP client. This is the per-request protocol layer.
 * - [decorateModel]: strips [LLMCapability.ToolChoice] from a model so Koog's
 *   `subgraphWithTask` re-prompts on text-only responses instead of throwing.
 *   This is the per-run capability layer paired with [decorateHttpClient].
 * - [finishTool]: the lenient `finalize_task_result` tool that tolerates a
 *   JSON object (not only a string) as the manifest, plus the optional
 *   explanation/answered/activities protocol fields.
 *
 * The vendored `setupAssistSubgraphWithTask` copy
 * (`com.scoopai.core.agent.AssistSubgraphWithImagesKt`) is part of the same
 * compatibility family (Koog 1.1.1 graph structure + attach gating + text-only
 * escape); it lives beside [LenientStringFinishTool] because it needs internal
 * graph DSL access, and its file KDoc carries the same trigger/retirement
 * contract.
 *
 * When upgrading Koog, check each entry point's retirement condition and
 * delete what the new version fixed upstream.
 */
public object KoogCompat {

    /**
     * Wrap [delegate] with the shared HTTP-layer workaround chain.
     *
     * The result always applies the exponential-backoff retry policy from
     * [retryConfig]. With [openaiCompatibleOnly] = true it additionally
     * assembles the OpenAI-compatible workaround chain:
     *
     * - request side: [stripToolChoice] (unconditional; some gateways reject
     *   the `tool_choice` field outright) followed by any
     *   [extraRequestInterceptors] (debug logging sees the stripped body);
     * - response side (in order): [reasoningFallbackToContent] ->
     *   [malformedFunctionCallRetryHint] -> [normalizeMissingCreated]
     *   (created filled with 0L); [onResponse] observes the rewritten body.
     *
     * With [openaiCompatibleOnly] = false this is the plain retry decoration
     * used for native provider clients (deepseek/openai/openrouter via
     * [compatHttpClient], anthropic/ollama via their factory).
     *
     * [onRetryLog] receives one line per scheduled retry attempt; hosts plug
     * their own logger here to surface retries in their UI. null (the
     * default) keeps the module-logger sink [RetryLogs.default].
     *
     * Note [compatHttpClient] keeps its generic [responseRewriter] parameter
     * for non-openai-compatible reuse; no live call path passes one today.
     */
    public fun decorateHttpClient(
        delegate: KoogHttpClient,
        openaiCompatibleOnly: Boolean = false,
        extraRequestInterceptors: List<(String) -> String> = emptyList(),
        onResponse: ((String) -> Unit)? = null,
        retryConfig: HttpRetryConfig = HttpRetryConfig.DEFAULT,
        requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
        onRetryLog: RetryLog? = null,
    ): InterceptingHttpClient = compatHttpClient(
        delegate = delegate,
        retryConfig = retryConfig,
        requestTimeoutMillis = requestTimeoutMillis,
        interceptors = if (openaiCompatibleOnly) {
            listOf(::stripToolChoice) + extraRequestInterceptors
        } else {
            extraRequestInterceptors
        },
        responseRewriter = if (openaiCompatibleOnly) builtInResponseRewriter() else null,
        onResponse = onResponse,
        onRetryLog = onRetryLog ?: RetryLogs.default,
    )

    /**
     * Strip the [LLMCapability.ToolChoice] capability from [model] when
     * [stripToolChoice] is true.
     *
     * Trigger: Koog's `subgraphWithTask` throws "Subgraph with task must
     * always call tools" on a text-only response whenever the model claims
     * ToolChoice capability. Thinking models frequently answer with a
     * reasoning-only turn first, so the generator runs its subgraphs on the
     * stripped model and relies on Koog's re-prompt loop
     * (handleAssistantMessage) to recover. The HTTP-side
     * [stripToolChoice] (via [decorateHttpClient]) removes the wire-format
     * field that gateway would otherwise reject; these two layers are
     * complementary, not redundant.
     *
     * Retire when Koog routes `MessagePart.Reasoning`-bearing responses onto
     * the re-prompt edge (or gates setToolChoiceRequired on a real gateway
     * probe), making the capability strip unnecessary for reasoner models.
     */
    public fun decorateModel(model: LLModel, stripToolChoice: Boolean): LLModel =
        if (stripToolChoice) {
            model.copy(capabilities = model.capabilities?.filter { it != LLMCapability.ToolChoice })
        } else {
            model
        }

    /**
     * The lenient `finalize_task_result` finish tool
     * ([LenientStringFinishTool]).
     *
     * Trigger: Koog's `FinishTool.decodeOutput` throws
     * `IndexOutOfBoundsException(-1)` when the model passes the manifest as a
     * JSON object instead of a JSON string (a very common LLM behavior); the
     * error message is unactionable for the model and burns every retry. A
     * fresh instance is served per access so each subgraph owns its tool.
     *
     * Retire when Koog's FinishTool accepts object-or-string `result`
     * payloads leniently.
     */
    public val finishTool: Tool<FinalizePayload, FinalizePayload>
        get() = LenientStringFinishTool()

    /**
     * Response rewriter chain in the exact order the live factory used:
     * reasoning fallback first (so a recovered text response wins), then the
     * malformed-function-call hint (fires only on blank content), then
     * `created` normalization (always last so it sees the final body).
     */
    private fun builtInResponseRewriter(): (String) -> String = { raw ->
        normalizeMissingCreated(
            malformedFunctionCallRetryHint(reasoningFallbackToContent(raw)),
            0L,
        )
    }

    /**
     * Retry-only decoration kept for callers that need a custom
     * [responseRewriter] or interceptor list; [decorateHttpClient] is the
     * preferred seam.
     */
    internal fun compatHttpClient(
        delegate: KoogHttpClient,
        retryConfig: HttpRetryConfig,
        requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
        interceptors: List<(String) -> String>,
        responseRewriter: ((String) -> String)?,
        onResponse: ((String) -> Unit)?,
        onRetryLog: RetryLog? = RetryLogs.default,
    ): InterceptingHttpClient = InterceptingHttpClient(
        delegate = delegate,
        interceptors = interceptors,
        responseRewriter = responseRewriter,
        onResponse = onResponse,
        retryConfig = retryConfig,
        requestTimeoutMillis = requestTimeoutMillis,
        onRetryLog = onRetryLog,
    )
}
