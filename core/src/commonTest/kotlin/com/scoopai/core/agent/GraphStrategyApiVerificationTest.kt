package com.scoopai.core.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.subgraph
import ai.koog.agents.core.dsl.extension.asUserMessage
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.feature.withTesting
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphStrategyApiVerificationTest {

    /**
     * Verifies that `subgraph {}` can be used inside `strategy {}`
     * with `then` chaining, and the agent executes correctly.
     */
    @Test
    fun `subgraph pass-through compiles and runs`() = runBlocking {
        val promptExecutor = getMockExecutor {
            mockLLMAnswer("ok") onRequestContains "test"
            mockLLMAnswer("").asDefaultResponse
        }

        val s = strategy<String, String>("minimal") {
            val sg by subgraph<String, String>("pass") {
                nodeStart then nodeFinish
            }
            nodeStart then sg then nodeFinish
        }

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = s,
            agentConfig = AIAgentConfig(
                prompt = prompt("test") { system("You are a helpful assistant.") },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 10,
            ),
        ) {
            withTesting()
        }

        val result = agent.run("test input")
        assertEquals("test input", result)
    }

    /**
     * Verifies that a subgraph with an LLM node works.
     * Uses nodeLLMRequest() (input Message.User) + asUserMessage edge.
     */
    @Test
    fun `subgraph with LLM text response works`() = runBlocking {
        val promptExecutor = getMockExecutor {
            mockLLMAnswer("Hello from subgraph!") onRequestContains "hello"
            mockLLMAnswer("").asDefaultResponse
        }

        val s = strategy<String, String>("llm_subgraph") {
            val mySubgraph by subgraph<String, String>(name = "echo") {
                val nodeLLM by nodeLLMRequest()
                edge(nodeStart forwardTo nodeLLM asUserMessage { it })
                edge(nodeLLM forwardTo nodeFinish onTextMessage { true })
            }
            nodeStart then mySubgraph then nodeFinish
        }

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = s,
            agentConfig = AIAgentConfig(
                prompt = prompt("test") { system("You are a helpful assistant.") },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 10,
            ),
        ) {
            withTesting()
        }

        val result = agent.run("hello world")
        assertEquals("Hello from subgraph!", result)
    }

    /**
     * Verifies that a strategy with two sequential subgraphs works.
     * This mirrors the planned research → analyze → generate flow.
     */
    @Test
    fun `two sequential subgraphs work`() = runBlocking {
        val promptExecutor = getMockExecutor {
            // Subgraph 1 receives "phase1 input"
            mockLLMAnswer("Result from phase 1") onRequestContains "phase1"
            // Subgraph 2 receives the output of subgraph 1: "Result from phase 1"
            mockLLMAnswer("Result from phase 2") onRequestContains "Result from phase 1"
            mockLLMAnswer("").asDefaultResponse
        }

        val s = strategy<String, String>("sequential") {
            val sub1 by subgraph<String, String>("phase1") {
                val nodeLLM by nodeLLMRequest()
                edge(nodeStart forwardTo nodeLLM asUserMessage { it })
                edge(nodeLLM forwardTo nodeFinish onTextMessage { true })
            }
            val sub2 by subgraph<String, String>("phase2") {
                val nodeLLM by nodeLLMRequest()
                edge(nodeStart forwardTo nodeLLM asUserMessage { it })
                edge(nodeLLM forwardTo nodeFinish onTextMessage { true })
            }
            nodeStart then sub1 then sub2 then nodeFinish
        }

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = s,
            agentConfig = AIAgentConfig(
                prompt = prompt("test") { system("You are a helpful assistant.") },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 10,
            ),
        ) {
            withTesting()
        }

        val result = agent.run("phase1 input")
        assertEquals("Result from phase 2", result)
    }

    /**
     * Verifies that subgraph with llmParams parameter works.
     */
    @Test
    fun `subgraph with llmParams works`() = runBlocking {
        val promptExecutor = getMockExecutor {
            mockLLMAnswer("With params!") onRequestContains "test"
            mockLLMAnswer("").asDefaultResponse
        }

        val s = strategy<String, String>("params_test") {
            val sg by subgraph<String, String>(
                name = "withParams",
                llmParams = ai.koog.prompt.params.LLMParams(),
            ) {
                val nodeLLM by nodeLLMRequest()
                edge(nodeStart forwardTo nodeLLM asUserMessage { it })
                edge(nodeLLM forwardTo nodeFinish onTextMessage { true })
            }
            nodeStart then sg then nodeFinish
        }

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = s,
            agentConfig = AIAgentConfig(
                prompt = prompt("test") { system("You are a helpful assistant.") },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 10,
            ),
        ) {
            withTesting()
        }

        val result = agent.run("test input")
        assertEquals("With params!", result)
    }
}
