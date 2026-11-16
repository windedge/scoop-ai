package com.scoopai.core.llm

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.CompletableDeferred
import com.scoopai.core.llm.RequestTimeoutException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Focused tests for the LLM request black-hole fixes:
 * - sse() must be bounded by a total stream timeout, retryable with the same
 *   semantics as post (a slow-drip or stalled gateway must not hang the run);
 * - post() must carry an overridable per-request timeout (Koog's Java client
 *   default is a fixed 15 minutes);
 * - every retried attempt must emit one structured log line
 *   (attempt / delayMs / cause) through the [InterceptingHttpClient.onRetryLog]
 *   sink.
 *
 * All tests run on virtual time via [runTest]; the scripted delegate never
 * resumes for hanging attempts, so no assertion depends on wall-clock timing.
 */
class TimeoutAndRetryLoggingTest {

    // ---------------------------------------------------------------
    // Scripted delegate: each attempt consults the scheduler and either
    // hangs forever (mirrors JavaKoogHttpClient.sse reading ofLines() with
    // no body-level timeout), emits SSE data lines, fails, or replies.
    // ---------------------------------------------------------------

    private sealed interface Step {
        data object SuspendForever : Step
        data class Emit(val items: List<String>) : Step
        data class Fail(val error: Throwable) : Step
        data class Reply(val body: String) : Step
    }

    private class HangingHttpClient(
        private val scheduler: suspend (Int) -> Step,
    ) : KoogHttpClient {
        val postAttempts = AtomicInteger()
        val sseAttempts = AtomicInteger()

        override val clientName: String = "hanging"
        override fun close() {}

        override suspend fun <R : Any> get(
            path: String,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R = error("not used")

        override suspend fun <T : Any, R : Any> post(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R {
            val attempt = postAttempts.incrementAndGet()
            return when (val step = scheduler(attempt)) {
                is Step.SuspendForever -> hangForever()
                is Step.Emit -> error("post cannot Emit")
                is Step.Fail -> throw step.error
                is Step.Reply -> step.body as R
            }
        }

        override fun <T : Any, R : Any, O : Any> sse(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            dataFilter: (String?) -> Boolean,
            decodeStreamingResponse: (String) -> R,
            processStreamingChunk: (R) -> O?,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<O> = flow {
            val attempt = sseAttempts.incrementAndGet()
            when (val step = scheduler(attempt)) {
                is Step.SuspendForever -> hangForever()
                is Step.Emit -> for (item in step.items) {
                    // Mirror JavaKoogHttpClient: strip the SSE "data: " prefix before decoding.
                    val data = item.removePrefix("data: ")
                    if (dataFilter(data)) {
                        processStreamingChunk(decodeStreamingResponse(data))?.let { emit(it) }
                    }
                }
                is Step.Fail -> throw step.error
                is Step.Reply -> error("sse cannot Reply")
            }
        }

        override fun <T : Any> lines(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<String> = error("not used")

        private suspend fun hangForever(): Nothing = suspendCancellableCoroutine { }
    }

    private fun hangingClient(
        delegate: HangingHttpClient,
        requestTimeoutMillis: Long = 5_000L,
        maxRetries: Int = 2,
        onRetryLog: ((String) -> Unit)? = null,
    ): InterceptingHttpClient = InterceptingHttpClient(
        delegate = delegate,
        retryConfig = HttpRetryConfig(maxRetries = maxRetries, initialDelayMs = 1_000L, maxDelayMs = 16_000L),
        requestTimeoutMillis = requestTimeoutMillis,
        onRetryLog = onRetryLog?.let { RetryLog(it) } ?: RetryLogs.default,
    )

    // ---------------------------------------------------------------
    // 1. sse() gating: a stalled stream must hit the total timeout and
    //    surface a retryable failure (not hang forever, not cancel the run)
    // ---------------------------------------------------------------

    @Test
    fun `sse stream that never completes fails with a retryable timeout`() = runTest {
        val delegate = HangingHttpClient { Step.SuspendForever }
        val client = hangingClient(delegate)

        val error = assertFailsWith<RequestTimeoutException> {
            client.sse(
                path = "chat/completions",
                requestBody = "{}",
                requestBodyType = String::class,
                decodeStreamingResponse = { it },
                processStreamingChunk = { it },
            ).collect { }
        }

        // The timeout message must stay retryable (isRetryableHttpException
        // looks for "timed out"/"timeout" in the message).
        assertTrue("timed out" in error.message!!.lowercase(), "unexpected message: ${error.message}")
        // The wrapper aborted the hanging attempt and retried until the budget
        // ran out (maxRetries=2): 1 initial + 2 retries, all timing out.
        assertEquals(3, delegate.sseAttempts.get())
    }

    // ---------------------------------------------------------------
    // 2. sse() retry: after a timed-out first attempt the flow must be
    //    re-collected and the retry's chunks delivered
    // ---------------------------------------------------------------

    @Test
    fun `sse timeout is retried and the retry attempt succeeds`() = runTest {
        val completed = CompletableDeferred<Unit>()
        val delegate = HangingHttpClient { attempt ->
            if (attempt == 1) Step.SuspendForever else Step.Emit(listOf("data: one", "data: two"))
        }
        val client = hangingClient(delegate)

        val received = mutableListOf<String>()
        // Collect off the test body so the timeout-triggered retry (a second
        // collection attempt) becomes observable inside one virtual scheduler.
        val job = launch {
            client.sse(
                path = "chat/completions",
                requestBody = "{}",
                requestBodyType = String::class,
                decodeStreamingResponse = { it },
                processStreamingChunk = { it },
            ).collect { chunk ->
                received.add(chunk)
                completed.complete(Unit)
            }
        }
        completed.await()
        job.cancel()

        assertEquals(listOf("one", "two"), received)
        assertEquals(2, delegate.sseAttempts.get())
    }

    // ---------------------------------------------------------------
    // 3. post() timeout: a hanging delegate (headers never arrive) must
    //    be aborted by the wrapper and surface as a retryable failure
    // ---------------------------------------------------------------

    @Test
    fun `post request timeout wraps a hanging delegate into a retryable timeout`() = runTest {
        val delegate = HangingHttpClient { Step.SuspendForever }
        val client = hangingClient(delegate)

        val error = assertFailsWith<RequestTimeoutException> {
            client.post("chat/completions", "{}", String::class, String::class, emptyMap(), emptyMap())
        }

        assertTrue("timed out" in error.message!!.lowercase(), "unexpected message: ${error.message}")
        // maxRetries=2: the aborted timeout counts against the retry budget,
        // so every attempt times out: 1 initial + 2 retries.
        assertEquals(3, delegate.postAttempts.get())
    }

    // ---------------------------------------------------------------
    // 4. Configurable per-request ceiling: the legacy fixed 15-minute
    //    ceiling must be overridable downward via requestTimeoutMillis
    // ---------------------------------------------------------------

    @Test
    fun `configured request timeout overrides the legacy 15 minute ceiling`() = runTest {
        val delegate = HangingHttpClient { Step.SuspendForever }
        // 900_000ms would keep the legacy ceiling; the configured 500ms
        // ceiling must abort the hanging attempt long before that.
        val client = hangingClient(delegate, requestTimeoutMillis = 500L, maxRetries = 0)

        val error = assertFailsWith<RequestTimeoutException> {
            client.post("chat/completions", "{}", String::class, String::class, emptyMap(), emptyMap())
        }
        assertTrue("timed out" in error.message!!.lowercase(), "unexpected message: ${error.message}")
        assertEquals(1, delegate.postAttempts.get())
    }

    // ---------------------------------------------------------------
    // 5. Retry observability: every retried attempt emits one structured
    //    line carrying attempt, delayMs and a cause summary
    // ---------------------------------------------------------------

    @Test
    fun `every retry attempt logs attempt delay and cause`() = runTest {
        val logLines = mutableListOf<String>()
        val delegate = HangingHttpClient { attempt ->
            if (attempt < 6) Step.Fail(IllegalStateException("Status code: 502. Bad Gateway"))
            else Step.Reply("""{"choices":[]}""")
        }
        val client = hangingClient(delegate, maxRetries = 5, onRetryLog = logLines::add)

        val result = client.post("chat/completions", "{}", String::class, String::class, emptyMap(), emptyMap())

        assertEquals("""{"choices":[]}""", result)
        assertEquals(6, delegate.postAttempts.get()) // 1 initial + 5 retries

        assertEquals(5, logLines.size, "one log line per retry attempt: $logLines")
        assertTrue(
            "attempt=1" in logLines[0] && "delayMs=1000" in logLines[0] && "502" in logLines[0],
            "first retry line must carry attempt, delay and cause: ${logLines[0]}",
        )
        assertTrue(
            "attempt=5" in logLines[4] && "delayMs=16000" in logLines[4],
            "last retry line must carry the capped backoff delay: ${logLines[4]}",
        )
    }

    // ---------------------------------------------------------------
    // 6. Retry observability for the sse timeout path: the timeout that
    //    triggers an sse retry must be logged too
    // ---------------------------------------------------------------

    @Test
    fun `sse timeout retries are logged with the timeout cause`() = runTest {
        val logLines = mutableListOf<String>()
        val completed = CompletableDeferred<Unit>()
        val delegate = HangingHttpClient { attempt ->
            if (attempt == 1) Step.SuspendForever else Step.Emit(listOf("data: ok"))
        }
        val client = hangingClient(delegate, onRetryLog = logLines::add)

        val received = mutableListOf<String>()
        val job = launch {
            client.sse(
                path = "chat/completions",
                requestBody = "{}",
                requestBodyType = String::class,
                decodeStreamingResponse = { it },
                processStreamingChunk = { it },
            ).collect { chunk ->
                received.add(chunk)
                completed.complete(Unit)
            }
        }
        completed.await()
        job.cancel()

        assertEquals(listOf("ok"), received)
        assertEquals(1, logLines.size, "the sse timeout retry must be logged: $logLines")
        assertTrue(
            "timed out" in logLines[0],
            "retry log line must carry the timeout cause: ${logLines[0]}",
        )
    }
}
