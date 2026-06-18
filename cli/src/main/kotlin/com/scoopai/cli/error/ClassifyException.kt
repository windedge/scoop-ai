package com.scoopai.cli.error

import ai.koog.agents.core.tools.ToolException
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.clients.LLMClientException
import java.io.IOException

/**
 * Classifies a [Throwable] into the appropriate [CliError] subclass.
 *
 * This is a pure function (no side effects), making it easy to unit-test
 * without mocking infrastructure.
 *
 * Mapping:
 * - [LLMClientException], [KoogHttpClientException] -> [LlmError] with exit code 65 (LLM_ERROR)
 * - [IOException] -> [IoError] with exit code 70 (IO_ERROR)
 * - [ToolException] -> [VerifyFailedError] with exit code 66 (VERIFY_FAILED)
 * - All other exceptions -> [GeneralError] with exit code 1 (GENERAL)
 */
fun classifyException(e: Throwable): CliError {
    return when (e) {
        is LLMClientException, is KoogHttpClientException ->
            LlmError("LLM request failed: ${e.message}", e)
        is IOException ->
            IoError("I/O failure: ${e.message}", e)
        is ToolException ->
            VerifyFailedError("Tool execution failed: ${e.message}", e)
        else ->
            GeneralError(e.message ?: e.javaClass.simpleName, e)
    }
}
