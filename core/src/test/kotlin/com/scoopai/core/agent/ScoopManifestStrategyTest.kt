package com.scoopai.core.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.feature.withTesting
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Strategy-level tests for the Scoop manifest generator graph strategy.
 *
 * These tests verify the graph structure and routing logic using mock LLMs.
 * They do NOT test prompt construction or tool execution — those belong
 * in ScoopManifestGeneratorMockTest.
 *
 * Subgraph internal structure (ReAct loop + thinking retry + parse node) is tested here
 * via text-only flows (mock returns text, so tool/retry edges are never taken).
 */
class ScoopManifestStrategyTest {

    @Test
    fun `no_windows_assets flows through all phases and returns text`() = runBlocking {
        // Each phase mock returns valid JSON that nodeParse can deserialize.
        // No custom promptBuilder is passed, so nodeBuildPrompt uses input.toString()
        // as the user message sent to the LLM mock.
        val researchJson = """{"status":"no_windows_assets","project":"linux-only/tool","alternatives":"Consider using WSL or a Linux VM."}"""
        val analyzeJson = """{"status":"ok","strategy":"portable_zip","entries":[],"bin":[]}"""
        val generateJson = """{"version":"0.0.0"}"""

        val promptExecutor = getMockExecutor {
            // Research phase: request contains the URL input
            mockLLMAnswer(researchJson) onRequestContains "https://github.com/linux-only/tool"
            // Analyze phase: request contains ResearchOutput.toString() e.g. "status=no_windows_assets"
            mockLLMAnswer(analyzeJson) onRequestContains "status=no_windows_assets"
            // Generate phase: request contains AnalyzeOutput.toString() e.g. "strategy=portable_zip"
            mockLLMAnswer(generateJson) onRequestContains "strategy=portable_zip"
            mockLLMAnswer("").asDefaultResponse
        }

        val strategy = scoopManifestStrategy()

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = strategy,
            agentConfig = AIAgentConfig(
                prompt = prompt("test") { system("You are a helpful assistant.") },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 30,
            ),
        ) {
            withTesting()
        }

        val result = agent.run("https://github.com/linux-only/tool")
        // Linear flow: research → analyze → generate all run, final output is generate's text
        assertTrue(result.isNotBlank(), "Should return some text, got: $result")
    }

    @Test
    fun `full 3-phase flow produces manifest`() = runBlocking {
        val researchJson = """{"status":"ok","project":"sharkdp/fd","version":"10.2.0","windows_assets":[{"url":"https://example.com/fd.zip","architecture":"64bit","asset_type":"zip","source":"release_api"}]}"""
        val analyzeJson = """{"status":"ok","strategy":"portable_zip","entries":[{"architecture":"64bit","url":"https://example.com/fd.zip","hash":"abc123","extract_dir":"fd-10.2.0"}],"bin":["fd.exe"]}"""
        val manifestJson = """{"version":"10.2.0","architecture":{"64bit":{"url":"https://example.com/fd.zip","hash":"abc123"}},"bin":["fd.exe"]}"""

        val promptExecutor = getMockExecutor {
            // Research phase: request contains the URL
            mockLLMAnswer(researchJson) onRequestContains "https://github.com/sharkdp/fd"
            // Analyze phase: request contains ResearchOutput.toString() which includes "project=sharkdp/fd"
            mockLLMAnswer(analyzeJson) onRequestContains "project=sharkdp/fd"
            // Generate phase: request contains AnalyzeOutput.toString() which includes "strategy=portable_zip"
            mockLLMAnswer("Here is the manifest:\n```json\n$manifestJson\n```") onRequestContains "strategy=portable_zip"
            mockLLMAnswer("").asDefaultResponse
        }

        val strategy = scoopManifestStrategy()

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = strategy,
            agentConfig = AIAgentConfig(
                prompt = prompt("test") { system("You are a helpful assistant.") },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 50,
            ),
        ) {
            withTesting()
        }

        val result = agent.run("https://github.com/sharkdp/fd")
        assertTrue(result.contains("10.2.0"), "Result should contain version, got: $result")
    }

    @Test
    fun `parse node retries when LLM returns invalid JSON`() = runBlocking {
        val researchJson = """{"status":"ok","project":"retry-test/repo","version":"1.0.0","windows_assets":[]}"""
        val analyzeJson = """{"status":"ok","strategy":"portable_zip","entries":[],"bin":[]}"""

        val promptExecutor = getMockExecutor {
            // Research first call: request contains URL → returns invalid text (triggers parse retry)
            mockLLMAnswer("This is not valid JSON") onRequestContains "https://github.com/retry-test/repo"
            // Research retry call: request contains error feedback ("could not be parsed") → valid JSON
            mockLLMAnswer(researchJson) onRequestContains "could not be parsed"
            // Analyze phase: request contains "project=retry-test/repo" from ResearchOutput.toString()
            mockLLMAnswer(analyzeJson) onRequestContains "retry-test/repo"
            // Default for generate
            mockLLMAnswer("""{"version":"1.0.0"}""").asDefaultResponse
        }

        val strategy = scoopManifestStrategy()

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = strategy,
            agentConfig = AIAgentConfig(
                prompt = prompt("test") { system("You are a helpful assistant.") },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 50,
            ),
        ) {
            withTesting()
        }

        val result = agent.run("https://github.com/retry-test/repo")
        // Despite the first research response being invalid JSON, the retry should succeed
        // and the full 3-phase flow should complete
        assertTrue(result.isNotBlank(), "Should return some text after retry, got: $result")
    }

    @Test
    fun `flow continues via fallback when all parse attempts fail`() = runBlocking {
        // All LLM responses are non-JSON text → nodeParse fails 3 times → falls back to raw text
        val notJson = "This is plain text without any JSON"

        val promptExecutor = getMockExecutor {
            mockLLMAnswer(notJson).asDefaultResponse
        }

        val strategy = scoopManifestStrategy()

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = strategy,
            agentConfig = AIAgentConfig(
                prompt = prompt("test") { system("You are a helpful assistant.") },
                model = DeepSeekModels.DeepSeekV4Flash,
                maxAgentIterations = 30,
            ),
        ) {
            withTesting()
        }

        val result = agent.run("https://github.com/test/repo")
        // Should not crash: all 3 phases execute, parse nodes fallback to raw text
        assertTrue(result.isNotBlank(), "Should return fallback text, got: $result")
    }

    @Test
    fun `parseResearchStatus extracts status from JSON`() {
        assertEquals("ok", parseResearchStatus("""{"status":"ok"}"""))
        assertEquals("no_windows_assets", parseResearchStatus("""{"status":"no_windows_assets"}"""))
        assertEquals("unknown", parseResearchStatus("not json"))
        assertEquals("unknown", parseResearchStatus("""{"noStatus":true}"""))
        assertEquals("unknown", parseResearchStatus(""))
    }
}
