package com.scoopai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.ToolRegistryBuilder
import ai.koog.agents.core.tools.reflect.ToolSet
import com.scoopai.core.agent.PhaseTools
import com.scoopai.core.agent.tools.ToolCallBudget
import com.scoopai.core.tools.api.ArchiveInspector
import com.scoopai.core.tools.api.GitHubApi
import com.scoopai.core.tools.api.HashComputer
import com.scoopai.core.tools.api.ManifestIo
import com.scoopai.core.tools.api.ScoopVerifier
import com.scoopai.core.tools.api.WebFetcher
import com.scoopai.tools.archive.ZipArchiveInspector
import com.scoopai.tools.cache.ScoopCacheManager
import com.scoopai.tools.cache.formatFileSize
import com.scoopai.tools.github.JdkHttpGitHubApi
import com.scoopai.tools.hash.StreamingHashComputer
import com.scoopai.tools.manifest.FileManifestIo
import com.scoopai.tools.interaction.Question
import com.scoopai.tools.interaction.QuestionOption
import com.scoopai.tools.interaction.SafeAskUser
import com.scoopai.tools.verify.ProcessScoopVerifier
import com.scoopai.tools.web.JdkHttpWebFetcher

/**
 * Set of tool names that are dynamically created at runtime (not registered in the factory).
 * These are excluded from [validatePhaseCoverage] to avoid false positives.
 */
private val DYNAMIC_TOOL_NAMES: Set<String> = setOf(PhaseTools.REVIEW_MANIFEST)

object DefaultToolRegistryFactory {
    private var cacheManager: ScoopCacheManager? = null
    private var toolBudget: ToolCallBudget? = null

    /**
     * @param nonInteractive when non-null, sets [SafeAskUser.nonInteractiveOverride]
     */
    fun create(
        cache: ScoopCacheManager = ScoopCacheManager(),
        githubToken: String? = null,
        gitHubApi: GitHubApi = JdkHttpGitHubApi(githubToken = githubToken),
        webFetcher: WebFetcher = JdkHttpWebFetcher(),
        manifestIo: ManifestIo = FileManifestIo(),
        scoopVerifier: ScoopVerifier? = null,
        extraTools: List<Tool<*, *>> = emptyList(),
        nonInteractive: Boolean? = null,
        askUserHandler: (suspend (String) -> String)? = null,
        questionnaireHandler: (suspend (List<Question>) -> String)? = null,
    ): ToolRegistry {
        if (nonInteractive != null) {
            SafeAskUser.nonInteractiveOverride = nonInteractive
        }
        SafeAskUser.customHandler = askUserHandler
        SafeAskUser.questionnaireHandler = questionnaireHandler

        val isNonInteractive = nonInteractive ?: SafeAskUser.isNonInteractive()
        if (questionnaireHandler != null) {
            cache.downloadConfirmer = { url, sizeBytes ->
                val fileName = url.substringBefore("?").substringAfterLast("/")
                val sizeInfo = if (sizeBytes > 0) " (${formatFileSize(sizeBytes)})" else ""
                val prompt = "Ready to download installer/archive '$fileName'$sizeInfo for analysis from:\n$url"
                val q = Question(
                    id = "download_confirm",
                    label = "Download",
                    prompt = prompt,
                    options = listOf(
                        QuestionOption(label = "Download and proceed", description = "Download $fileName into cache for SHA-256 and archive inspection"),
                        QuestionOption(label = "Skip downloading", description = "Skip downloading; agent will proceed with alternative information"),
                    ),
                    allowOther = true,
                )
                val response = questionnaireHandler.invoke(listOf(q))
                val trimmed = response.trim().lowercase()
                !(trimmed == "n" || trimmed == "no" || trimmed == "cancel" || trimmed == "skip" || trimmed == "abort" || trimmed == "false" || trimmed.contains("skip downloading"))
            }
        } else if (askUserHandler != null) {
            cache.downloadConfirmer = { url, sizeBytes ->
                val fileName = url.substringBefore("?").substringAfterLast("/")
                val sizeInfo = if (sizeBytes > 0) " (${formatFileSize(sizeBytes)})" else ""
                val prompt = "Ready to download installer/archive '$fileName'$sizeInfo for analysis from:\n$url\nProceed with download?"
                val response = askUserHandler.invoke(prompt)
                val trimmed = response.trim().lowercase()
                !(trimmed == "n" || trimmed == "no" || trimmed == "cancel" || trimmed == "skip" || trimmed == "abort" || trimmed == "false")
            }
        } else if (isNonInteractive) {
            // Non-interactive mode (e.g. CLI -n): skip confirmation, download directly
            cache.downloadConfirmer = null
        } else {
            // Interactive CLI mode: confirm with user via console/stdin
            cache.downloadConfirmer = { url, sizeBytes ->
                val fileName = url.substringBefore("?").substringAfterLast("/")
                val sizeInfo = if (sizeBytes > 0) " (${formatFileSize(sizeBytes)})" else ""
                val prompt = "Ready to download installer/archive '$fileName'$sizeInfo for analysis from:\n$url"
                val q = Question(
                    id = "download_confirm",
                    label = "Download",
                    prompt = prompt,
                    options = listOf(
                        QuestionOption(label = "Download and proceed", description = "Download $fileName into cache for inspection"),
                        QuestionOption(label = "Skip downloading", description = "Skip downloading this archive"),
                    ),
                    allowOther = true,
                )
                val response = SafeAskUser.execute(SafeAskUser.Args(questions = listOf(q)))
                val trimmed = response.trim().lowercase()
                !(trimmed == "n" || trimmed == "no" || trimmed == "cancel" || trimmed == "skip" || trimmed == "abort" || trimmed == "false" || trimmed.contains("skip downloading"))
            }
        }
        cacheManager = cache
        toolBudget = ToolCallBudget()
        val budget = toolBudget
        val hashComputer = StreamingHashComputer(cache)
        val archiveInspector = ZipArchiveInspector(cache, budget = budget)
        val effectiveWebFetcher = if (webFetcher is JdkHttpWebFetcher) {
            JdkHttpWebFetcher(budget = budget)
        } else {
            webFetcher
        }

        return ToolRegistry {
            registerIfToolSet(gitHubApi)
            registerIfToolSet(effectiveWebFetcher)
            registerIfToolSet(hashComputer)
            registerIfToolSet(archiveInspector)
            registerIfToolSet(manifestIo)
            registerIfToolSet(scoopVerifier)
            tool(SafeAskUser)
            tools(extraTools)
        }
    }

    /** Returns current cache manager instance, if configured. */
    fun getCacheManager(): ScoopCacheManager? = cacheManager

    /** Returns current tool budget instance, if configured. */
    fun getToolBudget(): ToolCallBudget? = toolBudget

    /**
     * Validates that the tools in this registry contain all tools referenced
     * in [PhaseTools] constants. Returns a list of missing tool names.
     * Call this in tests to catch rename regressions at test time.
     */
    fun validatePhaseCoverage(registry: ToolRegistry): List<String> {
        val allNames = PhaseTools.RESEARCH_TOOL_NAMES +
            PhaseTools.ANALYZE_TOOL_NAMES +
            PhaseTools.GENERATE_TOOL_NAMES - DYNAMIC_TOOL_NAMES
        val registeredNames = registry.tools.map { it.name }.toSet()
        return (allNames - registeredNames).toList()
    }

    /** Clean up resources. Call after agent run finishes. */
    fun cleanup() {
        SafeAskUser.customHandler = null
        SafeAskUser.questionnaireHandler = null
        cacheManager?.downloadConfirmer = null
        cacheManager?.cleanup()
        cacheManager = null
        toolBudget = null
    }

    private fun ToolRegistryBuilder.registerIfToolSet(obj: Any?) {
        (obj as? ToolSet)?.let { tools(it.asTools()) }
    }
}
