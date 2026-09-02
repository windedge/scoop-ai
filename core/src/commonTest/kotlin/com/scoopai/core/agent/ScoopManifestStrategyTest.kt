package com.scoopai.core.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.SubgraphWithTaskUtils
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.agents.ext.agent.FinishTool
import ai.koog.agents.testing.feature.withTesting
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.toKoogJSONObject
import ai.koog.serialization.typeToken
import com.scoopai.core.AssistContext
import com.scoopai.core.ManifestResult
import com.scoopai.core.SessionRequest
import com.scoopai.core.observer.GenerationObserver
import com.scoopai.core.agent.prompt.buildAnalyzePrompt
import com.scoopai.core.agent.prompt.buildGeneratePrompt
import com.scoopai.core.agent.prompt.buildAssistPrompt
import com.scoopai.core.agent.prompt.buildRepairPrompt
import com.scoopai.core.agent.prompt.buildResearchPrompt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Strategy-level tests for the Scoop manifest generator graph strategy.
 *
 * Uses subgraphWithTask: mock LLM returns `finalize_task_result` tool calls
 * with structured data, simulating the real LLM behavior.
 */
class ScoopManifestStrategyTest {

    @OptIn(InternalAgentToolsApi::class)
    private val researchFinishTool = FinishTool<ResearchOutput>(typeToken<ResearchOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val analyzeFinishTool = FinishTool<AnalyzeOutput>(typeToken<AnalyzeOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val generateFinishTool = FinishTool<String>(typeToken<String>())

    @OptIn(InternalAgentToolsApi::class)
    private val refineFinishTool = FinishTool<String>(typeToken<String>())

    /** A [ManifestIo] that always reports a validation failure (used to drive the repair loop to exhaustion). */
    private val failingManifestIo = object : com.scoopai.core.tools.api.ManifestIo {
        override fun validate(json: String?): String =
            """{"valid":false,"errors":["sample structural validation error"]}"""
        override fun write(json: String?, path: String?): String = ""
        override fun read(path: String?): String = ""
    }

    /**
     * Model without ToolChoice capability, used to simulate the generate subgraph environment
     * where normalize/verify must execute without tool_choice forced.
     */
    private val modelWithoutToolChoice: LLModel = DeepSeekModels.DeepSeekV4Flash.copy(
        capabilities = DeepSeekModels.DeepSeekV4Flash.capabilities?.filter { it != LLMCapability.ToolChoice }
    )

    private fun createTestAgent(
        promptExecutor: PromptExecutor,
        maxIterations: Int = 50,
        model: LLModel = DeepSeekModels.DeepSeekV4Flash,
        manifestIo: com.scoopai.core.tools.api.ManifestIo? = null,
        onPhase: ((String) -> Unit)? = null,
        observer: GenerationObserver = GenerationObserver(),
    ): AIAgent<SessionRequest, ManifestResult> {
        val strategy = scoopManifestStrategy(
            researchPromptBuilder = { buildResearchPrompt(it, null) },
            analyzePromptBuilder = { buildAnalyzePrompt(it) },
            generatePromptBuilder = { skeleton, analyze, _ -> buildGeneratePrompt(skeleton, analyze, null) },
            assistPromptBuilder = { if (it.explanatory) buildAssistPrompt(it) else buildRepairPrompt(it) },
            manifestIo = manifestIo,
            onPhase = onPhase,
            observer = observer,
        )
        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry.EMPTY,
            strategy = strategy,
            agentConfig = AIAgentConfig(
                prompt = prompt("test") { system("You are a helpful assistant.") },
                model = model,
                maxAgentIterations = maxIterations,
            ),
        ) {
            withTesting()
        }
    }

    private suspend fun AIAgent<SessionRequest, ManifestResult>.runUrl(url: String): ManifestResult =
        run(SessionRequest.Generate(url))

    @Test
    fun `no_windows_assets early-finishes without analyze or generate`() = runBlocking {
        val researchResult = ResearchOutput(
            status = ResearchStatus.NO_WINDOWS_ASSETS,
            project = "linux-only/tool",
            alternatives = "Consider using WSL or a Linux VM."
        )

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val result = createTestAgent(promptExecutor, maxIterations = 30)
            .runUrl("https://github.com/linux-only/tool")
        assertTrue(result.noAssets, "Should have noAssets=true")
        assertTrue(result.manifest.isBlank(), "Should return empty manifest, got: ${result.manifest}")
    }

    @Test
    fun `full 3-phase flow produces manifest`() = runBlocking {
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "sharkdp/fd",
            version = "10.2.0",
            windows_assets = listOf(
                WindowsAsset(
                    url = "https://example.com/fd.zip",
                    architecture = "64bit",
                    asset_type = "zip",
                    source = "release_api"
                )
            )
        )
        val analyzeResult = AnalyzeOutput(
            status = AnalyzeStatus.OK,
            strategy = "portable_zip",
            entries = listOf(
                AssetEntry(
                    architecture = "64bit",
                    url = "https://example.com/fd.zip",
                    hash = "abc123",
                    extract_dir = "fd-10.2.0"
                )
            ),
            bin = emptyList()
        )
        val generateResult = """{"version":"10.2.0","architecture":{"64bit":{"url":"https://example.com/fd.zip","hash":"abc123"}},"bin":["fd.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, generateResult) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val result = createTestAgent(promptExecutor)
            .runUrl("https://github.com/sharkdp/fd")
        assertTrue(result.manifest.contains("10.2.0"), "Result should contain version, got: $result")
        // Normal pass (verify succeeds) must NOT be flagged as exhausted.
        assertTrue(!result.exhausted, "Normal pass should have exhausted=false")
        assertTrue(result.issues.isEmpty(), "Normal pass should have empty issues")
    }

    @Test
    fun `verify phase fires onPhase event and records observer statistics`() = runBlocking {
        // The verify node must emit onPhase("verify") and pair enterPhase/exitPhase
        // exactly once per execution, so the observer snapshot gains one "verify"
        // StageInfo per verify pass (architecture proposal 4.6).
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "sharkdp/fd",
            version = "10.2.0",
            windows_assets = listOf(
                WindowsAsset(
                    url = "https://example.com/fd.zip",
                    architecture = "64bit",
                    asset_type = "zip",
                    source = "release_api"
                )
            )
        )
        val analyzeResult = AnalyzeOutput(
            status = AnalyzeStatus.OK,
            strategy = "portable_zip",
            entries = listOf(
                AssetEntry(
                    architecture = "64bit",
                    url = "https://example.com/fd.zip",
                    hash = "abc123",
                    extract_dir = "fd-10.2.0"
                )
            ),
            bin = emptyList()
        )
        val generateResult = """{"version":"10.2.0","architecture":{"64bit":{"url":"https://example.com/fd.zip","hash":"abc123"}},"bin":["fd.exe"]}"""

        val phases = mutableListOf<String>()
        val observer = GenerationObserver()
        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, generateResult) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val result = createTestAgent(promptExecutor, onPhase = { phases.add(it) }, observer = observer)
            .runUrl("https://github.com/sharkdp/fd")

        assertTrue(
            "verify" in phases,
            "onPhase must fire for the verify phase, got: $phases",
        )
        // Phases must be reported in execution order; verify comes after generate.
        assertEquals(
            listOf("research", "analyze", "generate", "verify"),
            phases,
            "onPhase sequence for a successful generate flow",
        )
        val verifyStages = observer.snapshot().filter { it.phaseName == "verify" }
        assertEquals(
            1,
            verifyStages.size,
            "one successful pass must yield exactly one completed verify StageInfo, got: ${observer.snapshot()}",
        )
        assertTrue(
            verifyStages[0].durationMs >= 0,
            "verify StageInfo duration must be non-negative",
        )
    }

    @Test
    fun `repair loop exhausted after max retries marks result exhausted with issues`() = runBlocking {
        // Given: research + analyze + generate produce valid data, but the deterministic
        // verify node always fails validation (failingManifestIo). The repair loop runs
        // up to maxRetries=3 times and then gives up.
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "sharkdp/fd",
            version = "10.2.0",
            windows_assets = listOf(
                WindowsAsset(
                    url = "https://example.com/fd.zip",
                    architecture = "64bit",
                    asset_type = "zip",
                    source = "release_api"
                )
            )
        )
        val analyzeResult = AnalyzeOutput(
            status = AnalyzeStatus.OK,
            strategy = "portable_zip",
            entries = listOf(
                AssetEntry(
                    architecture = "64bit",
                    url = "https://example.com/fd.zip",
                    hash = "abc123",
                    extract_dir = "fd-10.2.0"
                )
            ),
            bin = emptyList()
        )
        val manifestJson = """{"version":"10.2.0","architecture":{"64bit":{"url":"https://example.com/fd.zip","hash":"abc123"}},"bin":["fd.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            // Assist/repair subgraph is invoked up to 3 times (retryCount 1..3), each time verify still fails.
            mockLLMToolCall(refineFinishTool, manifestJson) onRequestContains "manifest repairer"
            mockLLMAnswer("").asDefaultResponse
        }

        // When: agent runs through all phases with a validator that always rejects.
        val result = createTestAgent(promptExecutor, maxIterations = 200, manifestIo = failingManifestIo)
            .runUrl("https://github.com/sharkdp/fd")

        // Then: the result must be flagged as exhausted with the unresolved issues attached.
        assertTrue(result.exhausted, "exhausted should be true after repair loop gives up")
        assertTrue(result.issues.isNotEmpty(), "issues should be non-empty, got: ${result.issues}")
        assertTrue(
            result.issues.any { it.contains("sample structural validation error") },
            "issues should include the validation error, got: ${result.issues}"
        )
        // The last known (invalid) manifest is still returned, but it must not read as success.
        assertTrue(result.manifest.isNotBlank(), "exhausted result should still carry last manifest")
    }

    @Test
    fun `suggested_app_name flows from research to ManifestResult`() = runBlocking {
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "ggml-org/llama.cpp",
            version = "b9264",
            suggested_app_name = "llama-cpp-sycl",
            windows_assets = listOf(
                WindowsAsset(
                    url = "https://example.com/llama-sycl.zip",
                    architecture = "64bit",
                    asset_type = "zip",
                    source = "release_api"
                )
            )
        )
        val analyzeResult = AnalyzeOutput(
            status = AnalyzeStatus.OK,
            strategy = "portable_zip",
            entries = listOf(
                AssetEntry(
                    architecture = "64bit",
                    url = "https://example.com/llama-sycl.zip",
                    hash = "abc123",
                    extract_dir = "llama-sycl"
                )
            ),
            bin = emptyList()
        )
        val generateResult = """{"version":"b9264","architecture":{"64bit":{"url":"https://example.com/llama-sycl.zip","hash":"abc123"}},"bin":["llama-cli.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, generateResult) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val result = createTestAgent(promptExecutor)
            .runUrl("https://github.com/ggml-org/llama.cpp")
        assertEquals("llama-cpp-sycl", result.suggestedAppName)
        assertTrue(result.manifest.contains("b9264"), "Result should contain version, got: ${result.manifest}")
    }

    @Test
    fun `suggested_app_name is null when research does not provide it`() = runBlocking {
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "sharkdp/fd",
            version = "10.2.0",
            windows_assets = listOf(
                WindowsAsset(
                    url = "https://example.com/fd.zip",
                    architecture = "64bit",
                    asset_type = "zip",
                    source = "release_api"
                )
            )
        )
        val analyzeResult = AnalyzeOutput(
            status = AnalyzeStatus.OK,
            strategy = "portable_zip",
            entries = listOf(
                AssetEntry(
                    architecture = "64bit",
                    url = "https://example.com/fd.zip",
                    hash = "abc123",
                    extract_dir = "fd-10.2.0"
                )
            ),
            bin = emptyList()
        )
        val generateResult = """{"version":"10.2.0","architecture":{"64bit":{"url":"https://example.com/fd.zip","hash":"abc123"}},"bin":["fd.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, generateResult) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val result = createTestAgent(promptExecutor)
            .runUrl("https://github.com/sharkdp/fd")
        assertEquals(null, result.suggestedAppName)
    }

    // =========================================================================
    // Post-process & verify execution tests
    //
    // normalize calls enforceArchitectureWrap which transforms a flat manifest
    // (url/hash at top level) into architecture-wrapped format.
    // verify returns null (pass) when manifestIo is null, allowing nodeFinish.
    //
    // If either node is bypassed, the manifest stays flat or the agent fails.
    // =========================================================================

    @Test
    fun `flat manifest from generate triggers enforceArchitectureWrap in normalize`() = runBlocking {
        // Given: research + analyze produce valid structured data, but the generate
        // phase returns a FLAT manifest (url at top level, no architecture block).
        // normalize should invoke enforceArchitectureWrap to fix this.
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "test/app",
            version = "1.0",
            windows_assets = listOf(
                WindowsAsset(
                    url = "https://example.com/app-x64.zip",
                    architecture = "64bit",
                    asset_type = "zip",
                    source = "release_api"
                )
            )
        )
        val analyzeResult = AnalyzeOutput(
            status = AnalyzeStatus.OK,
            strategy = "portable_zip",
            entries = listOf(
                AssetEntry(
                    architecture = "64bit",
                    url = "https://example.com/app-x64.zip",
                    hash = "abc123",
                )
            ),
            bin = listOf("app.exe"),
        )
        // Flat manifest: url/hash at top level, no architecture block
        val flatManifest = """{"version":"1.0","url":"https://example.com/app-x64.zip","hash":"abc123","bin":["app.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, flatManifest) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        // When: agent runs through all three phases (with modelWithoutToolChoice)
        val result = createTestAgent(promptExecutor, model = modelWithoutToolChoice)
            .runUrl("https://github.com/test/app")

        // Then: manifest must have been transformed by enforceArchitectureWrap
        val parsed = Json.parseToJsonElement(result.manifest).jsonObject
        assertTrue(parsed.containsKey("architecture"),
            "Manifest should have architecture block after normalize.enforceArchitectureWrap\nGot: ${result.manifest}")
        assertTrue("url" !in parsed,
            "Top-level 'url' key should NOT exist; it should be moved inside architecture block. Manifest: ${result.manifest}")
        val archBlock = parsed["architecture"]!!.jsonObject
        assertTrue(archBlock.containsKey("64bit"),
            "Architecture block should contain 64bit entry. Got keys: ${archBlock.keys}")
        assertTrue(result.manifest.isNotBlank(),
            "Result manifest should not be blank (proves verify->nodeFinish completed)")
    }

    @Test
    fun `generate mixed response with text and tool call still triggers normalize and verify`() = runBlocking {
        // Given: the LLM returns both text AND a finalize_task_result tool call
        // (common with thinking models that explain then call tools).
        // normalize and verify must still execute after the subgraph finishes.
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "test/app",
            version = "1.0",
            windows_assets = listOf(
                WindowsAsset(
                    url = "https://example.com/app-x64.zip",
                    architecture = "64bit",
                    asset_type = "zip",
                    source = "release_api"
                )
            )
        )
        val analyzeResult = AnalyzeOutput(
            status = AnalyzeStatus.OK,
            strategy = "portable_zip",
            entries = listOf(
                AssetEntry(
                    architecture = "64bit",
                    url = "https://example.com/app-x64.zip",
                    hash = "abc123",
                )
            ),
            bin = listOf("app.exe"),
        )
        val flatManifest = """{"version":"1.0","url":"https://example.com/app-x64.zip","hash":"abc123","bin":["app.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            // Mixed response: text + tool call (simulates thinking model behavior)
            mockLLMMixedResponse(
                toolCalls = listOf(generateFinishTool to flatManifest),
                responses = listOf("Here is the manifest I generated for you:")
            ) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        // When: agent runs through all three phases (with modelWithoutToolChoice)
        val result = createTestAgent(promptExecutor, model = modelWithoutToolChoice)
            .runUrl("https://github.com/test/app")

        // Then: manifest must have architecture block (proves normalize executed)
        val parsed = Json.parseToJsonElement(result.manifest).jsonObject
        assertTrue(parsed.containsKey("architecture"),
            "Mixed response: Manifest should have architecture block after normalize\nGot: ${result.manifest}")
        assertTrue("url" !in parsed,
            "Mixed response: Top-level 'url' key should NOT exist. Manifest: ${result.manifest}")
        assertTrue(result.manifest.isNotBlank(),
            "Mixed response: Result manifest should not be blank (proves verify->nodeFinish)")
    }

    // =========================================================================
    // P0 regression: object-form finalize_task_result in generate phase
    //
    // Koog 1.0.0 FinishTool<String> throws IndexOutOfBoundsException("Index -1
    // out of bounds for length 0") when the model passes the manifest as a JSON
    // object. The strategy now wires LenientStringFinishTool into generate/repair,
    // which stringifies object-form results instead of crashing.
    // =========================================================================

    /**
     * Test-only finalize tool that emits the result parameter as a JSON OBJECT
     * (simulating the real buggy model behavior on string-typed finish tools).
     */
    @OptIn(InternalAgentToolsApi::class)
    private val objectFormFinishTool = object : Tool<String, String>(
        argsType = typeToken<String>(),
        resultType = typeToken<String>(),
        descriptor = ToolDescriptor(
            name = SubgraphWithTaskUtils.FINALIZE_SUBGRAPH_TOOL_NAME,
            description = "test-only finalize emitting object-form result",
            requiredParameters = listOf(
                ToolParameterDescriptor(name = "result", description = "result", type = ToolParameterType.String)
            ),
        ),
    ) {
        override fun encodeArgs(args: String, serializer: JSONSerializer): JSONObject =
            JSONObject(mapOf("result" to Json.parseToJsonElement(args).jsonObject.toKoogJSONObject()))

        override fun decodeArgs(rawArgs: JSONObject, serializer: JSONSerializer): String = ""

        override fun encodeResult(result: String, serializer: JSONSerializer): JSONElement =
            encodeArgs(result, serializer)

        override fun decodeResult(rawResult: JSONElement, serializer: JSONSerializer): String = ""

        override suspend fun execute(args: String): String = args
    }

    @Test
    fun `object-form finalize result in generate phase is recovered by lenient finish tool`() = runBlocking {
        val researchResult = ResearchOutput(
            status = ResearchStatus.OK,
            project = "test/app",
            version = "1.0",
            windows_assets = listOf(
                WindowsAsset(
                    url = "https://example.com/app-x64.zip",
                    architecture = "64bit",
                    asset_type = "zip",
                    source = "release_api"
                )
            )
        )
        val analyzeResult = AnalyzeOutput(
            status = AnalyzeStatus.OK,
            strategy = "portable_zip",
            entries = listOf(
                AssetEntry(
                    architecture = "64bit",
                    url = "https://example.com/app-x64.zip",
                    hash = "abc123",
                )
            ),
            bin = listOf("app.exe"),
        )
        val flatManifest = """{"version":"1.0","url":"https://example.com/app-x64.zip","hash":"abc123","bin":["app.exe"]}"""

        val promptExecutor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            // The model passes the manifest as an object, not as a string
            mockLLMToolCall(objectFormFinishTool, flatManifest) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }

        val result = createTestAgent(promptExecutor, model = modelWithoutToolChoice)
            .runUrl("https://github.com/test/app")

        assertTrue(
            result.manifest.contains("1.0"),
            "manifest must survive object-form finalize (no 'index -1' crash). Got: " + result.manifest,
        )
        assertTrue(
            result.manifest.contains("architecture"),
            "normalize must still run after object-form finalize. Got: " + result.manifest,
        )
    }

    @Test
    fun `assist request branch in unified session graph runs successfully`() = runBlocking {
        val refinedManifest = """{"version":"1.0.1","architecture":{"64bit":{"url":"https://example.com/app.zip","hash":"abc123"}},"bin":["app.exe"]}"""
        val promptExecutor = getMockExecutor {
            mockLLMToolCall(refineFinishTool, refinedManifest) onRequestContains "manifest assistant"
            mockLLMAnswer("").asDefaultResponse
        }

        val agent = createTestAgent(promptExecutor)
        val request = SessionRequest.Assist(
            currentManifest = """{"version":"1.0.0"}""",
            instruction = "Update to 1.0.1",
            suggestedAppName = "test-app",
        )
        val result = agent.run(request)

        assertEquals("test-app", result.suggestedAppName)
        assertTrue(result.manifest.contains("1.0.1"), "Should contain refined version 1.0.1, got: ${result.manifest}")
        assertTrue(!result.exhausted, "Assist should succeed without being marked exhausted")
    }

    @Test
    fun `parseResearchStatus extracts status from JSON`() {
        assertEquals(ResearchStatus.OK, parseResearchStatus("""{"status":"ok"}"""))
        assertEquals(ResearchStatus.NO_WINDOWS_ASSETS, parseResearchStatus("""{"status":"no_windows_assets"}"""))
        assertEquals(ResearchStatus.OK, parseResearchStatus("not json"))
        assertEquals(ResearchStatus.OK, parseResearchStatus("""{"noStatus":true}"""))
        assertEquals(ResearchStatus.OK, parseResearchStatus(""))
    }

    // =========================================================================
    // parseReviewErrors: semantic review must be fail-closed.
    // approved=="true" short-circuits to empty; anything unexpected MUST NOT
    // silently pass the manifest (regression: parse failure / missing error
    // issues used to return an empty list, i.e. pass).
    // =========================================================================

    @Test
    fun `parseReviewErrors returns error issues from markdown-fenced JSON`() {
        val input = """
            ```json
            {"approved":false,"issues":[{"severity":"error","field":"checkver","message":"checkver is missing"}],"summary":"1 issue"}
            ```
        """.trimIndent()

        assertEquals(listOf("checkver is missing"), parseReviewErrors(input))
    }

    @Test
    fun `parseReviewErrors returns empty for approved true even when issues present`() {
        val input = """{"approved":true,"issues":[{"severity":"error","field":"checkver","message":"stale issue"}],"summary":"ok"}"""

        assertEquals(emptyList(), parseReviewErrors(input))
    }

    @Test
    fun `parseReviewErrors fails closed on plain narrative text`() {
        val input = "The manifest looks fine to me. No problems found."

        assertEquals(listOf(REVIEW_UNPARSEABLE), parseReviewErrors(input))
    }

    @Test
    fun `parseReviewErrors fails closed when approved false with no issues`() {
        val input = """{"approved":false,"issues":[],"summary":"rejected without details"}"""

        assertEquals(listOf(REVIEW_UNPARSEABLE), parseReviewErrors(input))
    }

    @Test
    fun `parseReviewErrors fails closed when approved false with only warnings`() {
        val input = """{"approved":false,"issues":[{"severity":"warning","field":"homepage","message":"use https"}],"summary":"1 warning"}"""

        assertEquals(listOf(REVIEW_UNPARSEABLE), parseReviewErrors(input))
    }

    @Test
    fun `parseReviewErrors returns error issues when approved false`() {
        val input = """{"approved":false,"issues":[{"severity":"error","field":"checkver","message":"checkver is missing"},{"severity":"warning","field":"homepage","message":"use https"}],"summary":"2 issues"}"""

        assertEquals(listOf("checkver is missing"), parseReviewErrors(input))
    }

    @Test
    fun `parseReviewErrors fails closed when JSON parses but declares nothing interpretable`() {
        val input = """{"foo":1}"""

        assertEquals(listOf(REVIEW_UNPARSEABLE), parseReviewErrors(input))
    }

    // =========================================================================
    // splitFeedback: reverse of errors.joinToString("\n- ", "- ").
    // =========================================================================

    @Test
    fun `splitFeedback round-trips joined error list`() {
        val errors = listOf("checkver is missing", "license should be GPL-3.0-or-later", "bin includes updater.exe")

        val feedback = errors.joinToString("\n- ", "- ")

        assertEquals(errors, splitFeedback(feedback))
    }

    @Test
    fun `splitFeedback splits an embedded newline inside an issue into separate entries`() {
        // Newlines inside an issue message are not preserved: feedback is a
        // line-based protocol, so a multi-line message becomes several issues.
        val errors = listOf("first line\nsecond line", "another")

        val feedback = errors.joinToString("\n- ", "- ")

        assertEquals(listOf("first line", "second line", "another"), splitFeedback(feedback))
    }

    @Test
    fun `splitFeedback drops blank issues and returns empty for blank feedback`() {
        assertEquals(emptyList(), splitFeedback(""))
        assertEquals(emptyList(), splitFeedback("   "))

        val feedback = listOf("real issue", "", "  ").joinToString("\n- ", "- ")

        assertEquals(listOf("real issue"), splitFeedback(feedback))
    }

    // =========================================================================
    // buildNoAssetsExplanation: deterministic engine-side synthesis for the
    // noAssets early-finish branch. Target style is one or two short user-facing
    // sentences: a fixed lead-in plus the FIRST sentence of alternatives/notes
    // (no labels, no listing of the remaining field content, total capped).
    // =========================================================================

    @Test
    fun `buildNoAssetsExplanation keeps only the first sentence of alternatives`() {
        val research = ResearchOutput(
            status = ResearchStatus.NO_WINDOWS_ASSETS,
            project = "linux-only/tool",
            alternatives = "Consider using WSL or a Linux VM. Cygwin may also work but is unsupported.",
        )

        val explanation = buildNoAssetsExplanation(research)

        assertEquals(
            "No Windows assets found for this project (linux-only/tool). Consider using WSL or a Linux VM.",
            explanation,
            "explanation must keep only the first alternatives sentence",
        )
    }

    @Test
    fun `buildNoAssetsExplanation falls back to the first sentence of notes when alternatives is blank`() {
        val research = ResearchOutput(
            status = ResearchStatus.NO_WINDOWS_ASSETS,
            project = "linux-only/tool",
            notes = "Only Makefile build targets are provided. Windows support is planned for later.",
        )

        val explanation = buildNoAssetsExplanation(research)

        assertEquals(
            "No Windows assets found for this project (linux-only/tool). Only Makefile build targets are provided.",
            explanation,
            "explanation must fall back to the first notes sentence when alternatives is blank",
        )
    }

    @Test
    fun `buildNoAssetsExplanation returns only the lead-in when alternatives and notes are blank`() {
        val research = ResearchOutput(
            status = ResearchStatus.NO_WINDOWS_ASSETS,
            project = "linux-only/tool",
        )

        val explanation = buildNoAssetsExplanation(research)

        assertEquals(
            "No Windows assets found for this project (linux-only/tool).",
            explanation,
        )
    }

    @Test
    fun `buildNoAssetsExplanation truncates overlong sentences and caps the total length`() {
        // A single alternatives sentence well past the 140-char detail budget,
        // with no sentence boundary inside it.
        val longSentence = "The upstream project only publishes source archives and building a Windows binary requires a self-hosted toolchain that is not available to end users."

        val plain = buildNoAssetsExplanation(
            ResearchOutput(
                status = ResearchStatus.NO_WINDOWS_ASSETS,
                project = "owner/repo",
                alternatives = longSentence,
            ),
        )!!

        // Detail is cut at a word boundary near 140 chars; the total stays
        // under the cap, so no ellipsis yet and the tail is dropped.
        assertTrue(plain.length <= 220, "plain explanation must stay under the total cap, got ${plain.length}: $plain")
        assertTrue(!plain.endsWith("..."), "plain explanation must not be ellipsized, got: $plain")
        assertTrue(
            plain.startsWith("No Windows assets found for this project (owner/repo). The upstream project"),
            "truncated detail must keep the sentence head, got: $plain",
        )
        assertTrue(!plain.contains("end users"), "truncated detail must drop the sentence tail, got: $plain")
        assertTrue(
            !plain.contains("Alternatives:") && !plain.contains("Notes:"),
            "explanation must not use field labels, got: $plain",
        )

        // A very long project name pushes the assembled text past the 220-char
        // total cap, so the whole explanation is truncated with an ellipsis.
        val capped = buildNoAssetsExplanation(
            ResearchOutput(
                status = ResearchStatus.NO_WINDOWS_ASSETS,
                project = "o/" + "p".repeat(60),
                alternatives = longSentence,
            ),
        )!!

        assertTrue(capped.length <= 220, "capped explanation must be at most 220 chars, got ${capped.length}")
        assertTrue(capped.endsWith("..."), "capped explanation must end with an ellipsis, got: $capped")
    }

    @Test
    fun `buildNoAssetsExplanation puts the project name in parentheses`() {
        val withProject = buildNoAssetsExplanation(
            ResearchOutput(status = ResearchStatus.NO_WINDOWS_ASSETS, project = "linux-only/tool"),
        )!!
        assertTrue(
            withProject.contains("No Windows assets found for this project (linux-only/tool)."),
            "project must be parenthesized after the lead-in, got: $withProject",
        )

        val withoutProject = buildNoAssetsExplanation(
            ResearchOutput(status = ResearchStatus.NO_WINDOWS_ASSETS),
        )!!
        assertTrue(
            !withoutProject.contains("("),
            "blank project must not produce empty parentheses, got: $withoutProject",
        )
    }

    @Test
    fun `buildNoAssetsExplanation returns non-null for bare research output`() {
        val research = ResearchOutput(status = ResearchStatus.NO_WINDOWS_ASSETS)

        val explanation = buildNoAssetsExplanation(research)

        assertEquals(
            "No Windows assets found for this project.",
            explanation,
            "deterministic synthesis must always produce a fallback text, got: $explanation",
        )
    }

    @Test
    fun `buildNoAssetsExplanation does not cut the sentence at an abbreviation like e g`() {
        val research = ResearchOutput(
            status = ResearchStatus.NO_WINDOWS_ASSETS,
            project = "linux-only/tool",
            alternatives = "Check tools that support POSIX, e.g. WSL or a Linux VM. Cross-compiling is out of scope.",
        )

        val explanation = buildNoAssetsExplanation(research)

        assertEquals(
            "No Windows assets found for this project (linux-only/tool). Check tools that support POSIX, e.g. WSL or a Linux VM.",
            explanation,
            "the period in e.g. must not be treated as a sentence boundary",
        )
    }

    // =========================================================================
    // assistPhaseName: the single source of truth for the subgraph phase name.
    // A user follow-up (explanatory=true) is "assist"; the internal repair loop
    // (explanatory=false) is "repair" (renamed from the old monolithic "refine").
    // =========================================================================

    @Test
    fun `assistPhaseName returns assist for a user follow-up`() {
        val ctx = AssistContext(
            manifest = "{}",
            feedback = "reviewer note",
            explanatory = true,
        )
        assertEquals("assist", assistPhaseName(ctx))
    }

    @Test
    fun `assistPhaseName returns repair for the internal repair loop`() {
        val ctx = AssistContext(
            manifest = "{}",
            feedback = "reviewer note",
            explanatory = false,
        )
        assertEquals("repair", assistPhaseName(ctx))
    }
}