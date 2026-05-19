package com.scoopai.core.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.feature.withTesting
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThinkingCompatibleStrategyMockTest {

    @Test
    fun `text response goes to finish`() = runBlocking {
        val promptExecutor = getMockExecutor {
            mockLLMAnswer("Final answer: fd is great!") onRequestContains "Research fd"
            mockLLMAnswer("").asDefaultResponse
        }

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry {},
            strategy = thinkingCompatibleSingleRunStrategy(),
            agentConfig = AIAgentConfig(
                prompt = prompt("test") {
                    system("You are a helpful assistant.")
                },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 3,
            ),
        ) {
            withTesting()
        }

        val result = agent.run("Research fd")
        assertTrue(result.contains("fd is great"), "Expected 'fd is great' in result, got: $result")
    }

    @Test
    fun `tool call then text response completes successfully`() = runBlocking {
        @Serializable
        data class EchoArgs(val message: String)

        val echoTool = object : SimpleTool<EchoArgs>(
            argsSerializer = EchoArgs.serializer(),
            name = "echo",
            description = "Echo back the input",
        ) {
            override suspend fun execute(args: EchoArgs): String = args.message
        }

        val toolRegistry = ToolRegistry {
            tool(echoTool)
        }

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(echoTool, EchoArgs("hello world")) onRequestContains "echo test"
            mockLLMAnswer("Done: hello world") onRequestContains "hello world"
            mockLLMAnswer("").asDefaultResponse
        }

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = thinkingCompatibleSingleRunStrategy(),
            agentConfig = AIAgentConfig(
                prompt = prompt("test") {
                    system("You are a helpful assistant with an echo tool.")
                },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 5,
            ),
        ) {
            withTesting()
        }

        val result = agent.run("echo test")
        assertTrue(result.isNotBlank(), "Result should not be blank, got: $result")
    }

    @Test
    fun `empty response returns empty string via text edge`() = runBlocking {
        // When mock returns empty text (which becomes MessagePart.Text("")),
        // the strategy routes via onTextMessage edge directly to finish.
        // Pure-reasoning retry/fallback path cannot be tested through getMockExecutor
        // because the current DSL only supports Text and Tool.Call response parts.
        val promptExecutor = getMockExecutor {
            mockLLMAnswer("").asDefaultResponse
        }

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry {},
            strategy = thinkingCompatibleSingleRunStrategy(),
            agentConfig = AIAgentConfig(
                prompt = prompt("test") {
                    system("You are a helpful assistant.")
                },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 5,
            ),
        ) {
            withTesting()
        }

        val result = agent.run("test")
        assertEquals("", result, "Empty LLM response should result in empty output")
    }
}
