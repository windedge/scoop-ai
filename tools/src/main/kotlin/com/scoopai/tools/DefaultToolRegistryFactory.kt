package com.scoopai.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.ToolSet
import com.scoopai.core.tools.api.ArchiveInspector
import com.scoopai.core.tools.api.GitHubApi
import com.scoopai.core.tools.api.HashComputer
import com.scoopai.core.tools.api.ManifestIo
import com.scoopai.core.tools.api.ScoopVerifier
import com.scoopai.core.tools.api.WebFetcher
import com.scoopai.tools.archive.ZipArchiveInspector
import com.scoopai.tools.github.KtorGitHubApi
import com.scoopai.tools.hash.StreamingHashComputer
import com.scoopai.tools.manifest.FileManifestIo
import com.scoopai.tools.interaction.SafeAskUser
import com.scoopai.tools.verify.ProcessScoopVerifier
import com.scoopai.tools.web.KtorWebFetcher

object DefaultToolRegistryFactory {
    fun create(
        gitHubApi: GitHubApi = KtorGitHubApi(),
        webFetcher: WebFetcher = KtorWebFetcher(),
        hashComputer: HashComputer = StreamingHashComputer(),
        archiveInspector: ArchiveInspector = ZipArchiveInspector(),
        manifestIo: ManifestIo = FileManifestIo(),
        scoopVerifier: ScoopVerifier? = null,
        extraTools: List<Tool<*, *>> = emptyList(),
    ): ToolRegistry = ToolRegistry {
        (gitHubApi as? ToolSet)?.let { tools(it.asTools()) }
        (webFetcher as? ToolSet)?.let { tools(it.asTools()) }
        (hashComputer as? ToolSet)?.let { tools(it.asTools()) }
        (archiveInspector as? ToolSet)?.let { tools(it.asTools()) }
        (manifestIo as? ToolSet)?.let { tools(it.asTools()) }
        (scoopVerifier as? ToolSet)?.let { tools(it.asTools()) }
        tool(SafeAskUser)
        extraTools.forEach { tool(it) }
    }
}
