package com.scoopai.cli.error

import ai.koog.agents.core.tools.ToolException
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.clients.LLMClientException
import com.scoopai.core.ScoopAiGenerationException
import java.io.IOException

/**
 * Classifies a [Throwable] into the appropriate [CliError] subclass.
 *
 * This is a pure function (no side effects), making it easy to unit-test
 * without mocking infrastructure.
 *
 * Mapping:
 * - [ScoopAiGenerationException] -> [LlmError] with exit code 65 (LLM_ERROR), message passed through untruncated
 * - [LLMClientException], [KoogHttpClientException] -> [LlmError] with exit code 65 (LLM_ERROR)
 * - [IOException] -> [IoError] with exit code 70 (IO_ERROR)
 * - [ToolException] -> [VerifyFailedError] with exit code 66 (VERIFY_FAILED)
 * - All other exceptions -> [GeneralError] with exit code 1 (GENERAL)
 */
/** Extract the first line of an exception message, falling back to class name. */
private fun firstLine(message: String?, className: String): String =
    message?.lineSequence()?.firstOrNull()?.trim()?.let { if (it.isNotEmpty()) it else null }
        ?: className

/**
 * Classifies a [Throwable] into the appropriate [CliError] subclass.
 *
 * This is a pure function (no side effects), making it easy to unit-test
 * without mocking infrastructure.
 *
 * Messages are truncated to the first line to prevent stack trace leakage
 * in user-facing output.
 *
 * Mapping:
 * - [ScoopAiGenerationException] -> [LlmError] with exit code 65 (LLM_ERROR), message passed through untruncated
 * - [LLMClientException], [KoogHttpClientException] -> [LlmError] with exit code 65 (LLM_ERROR)
 * - [IOException] -> [IoError] with exit code 70 (IO_ERROR)
 * - [ToolException] -> [VerifyFailedError] with exit code 66 (VERIFY_FAILED)
 * - All other exceptions -> [GeneralError] with exit code 1 (GENERAL)
 */
fun classifyException(e: Throwable): CliError {
    val message = firstLine(e.message, e.javaClass.simpleName)
    return when (e) {
        // Typed generation failure (e.g. model without function calling support).
        // Its message is crafted for end users (multi-line cause analysis + fix
        // hint), so it is passed through WITHOUT first-line truncation.
        is ScoopAiGenerationException ->
            LlmError(e.message ?: message, e)
        is LLMClientException, is KoogHttpClientException ->
            LlmError("LLM request failed: $message", e)
        is IOException ->
            IoError("I/O failure: $message", e)
        is ToolException ->
            VerifyFailedError("Tool execution failed: $message", e)
        else ->
            GeneralError(message, e)
    }
}
