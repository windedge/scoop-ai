package com.scoopai.core.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.ext.agent.FinishTool
import ai.koog.agents.testing.feature.withTesting
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.serialization.typeToken
import com.scoopai.core.ManifestResult
import com.scoopai.core.SessionRequest
import com.scoopai.core.agent.prompt.buildAnalyzePrompt
import com.scoopai.core.agent.prompt.buildGeneratePrompt
import com.scoopai.core.agent.prompt.buildResearchPrompt
import com.scoopai.core.tools.api.GitHubApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generate prompt receives the effective release's asset names through a
 * prompt-time provider. Tests here pin that channel: the default provider reads
 * the prefetch storage key (so a resumed run, whose prefetch node was restored
 * rather than re-executed, still sees the list) and an explicit provider wins
 * over the stored list.
 */
class ReleaseAssetNamesProviderTest {

    @OptIn(InternalAgentToolsApi::class)
    private val researchFinishTool = FinishTool<ResearchOutput>(typeToken<ResearchOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val analyzeFinishTool = FinishTool<AnalyzeOutput>(typeToken<AnalyzeOutput>())

    @OptIn(InternalAgentToolsApi::class)
    private val generateFinishTool = FinishTool<String>(typeToken<String>())

    private val releaseJson =
        """{"tag_name":"v1.0.0","prerelease":false,"assets":[""" +
            """{"name":"app-1.0.zip","browser_download_url":"https://example.com/app-1.0.zip","size":1000},""" +
            """{"name":"checksums.txt","browser_download_url":"https://example.com/checksums.txt","size":100}]}"""

    private fun stubGitHubApi(): GitHubApi = object : GitHubApi {
        override suspend fun parseGitHubUrl(url: String?) = """{"owner":"owner","repo":"repo"}"""
        override suspend fun fetchRepoInfo(owner: String?, repo: String?) =
            """{"full_name":"owner/repo","description":"test","default_branch":"main","stargazers_count":1}"""
        override suspend fun fetchLatestRelease(owner: String?, repo: String?) = releaseJson
        override suspend fun fetchAllReleases(owner: String?, repo: String?, perPage: Int) = """[]"""
        override suspend fun listReleaseAssets(owner: String?, repo: String?, tag: String?) = """[]"""
        override suspend fun fetchFileContent(owner: String?, repo: String?, path: String?) = """{"path":"LICENSE","content":"MIT"}"""
    }

    private val researchResult = ResearchOutput(
        status = ResearchStatus.OK,
        project = "owner/repo",
        version = "1.0.0",
        checksums_filename = "checksums.txt",
        windows_assets = listOf(
            WindowsAsset(
                url = "https://example.com/app-1.0.zip",
                architecture = "64bit",
                asset_type = "zip",
                source = "release_api",
            ),
        ),
    )

    private val analyzeResult = AnalyzeOutput(
        status = AnalyzeStatus.OK,
        strategy = "portable_zip",
        entries = listOf(
            AssetEntry(
                architecture = "64bit",
                url = "https://example.com/app-1.0.zip",
                hash = "abc123",
            ),
        ),
        bin = listOf("app.exe"),
    )

    private val manifestJson =
        """{"version":"1.0.0","architecture":{"64bit":{"url":"https://example.com/app-1.0.zip","hash":"abc123"}},"bin":["app.exe"]}"""

    private fun runGenerate(
        releaseAssetNamesProvider: (() -> List<String>)?,
        onPrompt: (List<String>, String) -> Unit,
    ): ManifestResult = runBlocking {
        val strategy = scoopManifestStrategy(
            researchPromptBuilder = { buildResearchPrompt(it, null) },
            analyzePromptBuilder = { buildAnalyzePrompt(it) },
            generatePromptBuilder = { skeleton, analyze, research, assetNames ->
                val built = buildGeneratePrompt(
                    skeleton,
                    analyze,
                    outputPath = null,
                    research = research,
                    checksumsAssetNames = assetNames,
                )
                onPrompt(assetNames, built)
                built
            },
            gitHubApi = stubGitHubApi(),
            releaseAssetNamesProvider = releaseAssetNamesProvider,
        )
        val executor = getMockExecutor {
            mockLLMToolCall(researchFinishTool, researchResult) onRequestContains "RESEARCH phase"
            mockLLMToolCall(analyzeFinishTool, analyzeResult) onRequestContains "ANALYSIS phase"
            mockLLMToolCall(generateFinishTool, manifestJson) onRequestContains "GENERATE phase"
            mockLLMAnswer("").asDefaultResponse
        }
        val agent = AIAgent(
            promptExecutor = executor,
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
        agent.run(SessionRequest.Generate("https://github.com/owner/repo"))
    }

    @Test
    fun `default provider hands the prefetch release asset names to the generate prompt`() {
        var captured: List<String> = emptyList()
        var promptText = ""

        runGenerate(releaseAssetNamesProvider = null) { assetNames, built ->
            captured = assetNames
            promptText = built
        }

        assertEquals(listOf("app-1.0.zip", "checksums.txt"), captured)
        assertTrue(
            promptText.contains("The research phase found a checksums file in the release assets: `checksums.txt`."),
            "matched asset must yield the strong MUST wording, got: $promptText",
        )
        assertTrue(
            promptText.contains("You MUST add an `autoupdate.hash` block"),
            "note must carry the MUST instruction, got: $promptText",
        )
    }

    @Test
    fun `explicit provider overrides the stored asset names at prompt-build time`() {
        var calls = 0
        var captured: List<String> = emptyList()
        var promptText = ""

        runGenerate(releaseAssetNamesProvider = {
            calls++
            listOf("other-file.zip")
        }) { assetNames, built ->
            captured = assetNames
            promptText = built
        }

        assertEquals(listOf("other-file.zip"), captured, "the provider, not the stored list, must win")
        assertTrue(calls >= 1, "provider must be consulted when the generate prompt is built")
        assertTrue(
            !promptText.contains("## Checksums Available"),
            "a checksums filename absent from the provider's list must drop the note, got: $promptText",
        )
    }
}
