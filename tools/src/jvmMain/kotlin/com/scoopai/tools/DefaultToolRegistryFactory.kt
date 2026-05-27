package com.scoopai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.ToolRegistryBuilder
import ai.koog.agents.core.tools.reflect.ToolSet
import com.scoopai.core.agent.PhaseTools
import com.scoopai.core.tools.api.ArchiveInspector
import com.scoopai.core.tools.api.GitHubApi
import com.scoopai.core.tools.api.HashComputer
import com.scoopai.core.tools.api.ManifestIo
import com.scoopai.core.tools.api.ScoopVerifier
import com.scoopai.core.tools.api.WebFetcher
import com.scoopai.tools.archive.ZipArchiveInspector
import com.scoopai.tools.cache.ScoopCacheManager
import com.scoopai.tools.github.JdkHttpGitHubApi
import com.scoopai.tools.hash.StreamingHashComputer
import com.scoopai.tools.manifest.FileManifestIo
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

    fun create(
        cache: ScoopCacheManager = ScoopCacheManager(),
        githubToken: String? = null,
        gitHubApi: GitHubApi = JdkHttpGitHubApi(githubToken = githubToken),
        webFetcher: WebFetcher = JdkHttpWebFetcher(),
        manifestIo: ManifestIo = FileManifestIo(),
        scoopVerifier: ScoopVerifier? = null,
        extraTools: List<Tool<*, *>> = emptyList(),
    ): ToolRegistry {
        cacheManager = cache
        val hashComputer = StreamingHashComputer(cache)
        val archiveInspector = ZipArchiveInspector(cache)

        return ToolRegistry {
            registerIfToolSet(gitHubApi)
            registerIfToolSet(webFetcher)
            registerIfToolSet(hashComputer)
            registerIfToolSet(archiveInspector)
            registerIfToolSet(manifestIo)
            registerIfToolSet(scoopVerifier)
            tool(SafeAskUser)
            extraTools.forEach { tool(it) }
        }
    }

    /** Get the cache manager used in the current run (for finalizing cache entries). */
    fun getCacheManager(): ScoopCacheManager? = cacheManager

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
        cacheManager?.cleanup()
        cacheManager = null
    }

    private fun ToolRegistryBuilder.registerIfToolSet(obj: Any?) {
        (obj as? ToolSet)?.let { tools(it.asTools()) }
    }
}
