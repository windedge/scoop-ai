package com.scoopai.core.agent

/**
 * System prompts for each phase of the Scoop manifest generation pipeline.
 *
 * Prompt content is loaded from `resources/prompts/` so that prompt
 * text can be updated without touching Kotlin code. Variable placeholders
 * like `{{RESEARCH_SCHEMA}}` are interpolated at load time.
 *
 * If resource loading fails (e.g. during unit tests without classpath resources),
 * the embedded fallback strings are used.
 */
object SystemPrompts {

    /** Phase 1: Research — gather project info, identify Windows assets */
    val RESEARCH: String by lazy {
        loadPrompt("prompts/research.txt") { RESEARCH_FALLBACK }
            .replace("{{RESEARCH_SCHEMA}}", PhaseSchemas.RESEARCH_SCHEMA)
    }

    /** Phase 2: Analyze — compute hashes, inspect archives */
    val ANALYZE: String by lazy {
        loadPrompt("prompts/analyze.txt") { ANALYZE_FALLBACK }
            .replace("{{ANALYZE_SCHEMA}}", PhaseSchemas.ANALYZE_SCHEMA)
    }

    /** Phase 3: Generate — create manifest, validate, write */
    val GENERATE: String by lazy {
        loadPrompt("prompts/generate.txt") { GENERATE_FALLBACK }
            .replace("{{REFERENCE_MANIFESTS}}", ReferenceManifests.ALL)
    }

    private fun loadPrompt(resourcePath: String, fallback: () -> String): String {
        return try {
            val stream = SystemPrompts::class.java.classLoader
                ?.getResourceAsStream(resourcePath)
            stream?.bufferedReader()?.use { it.readText() } ?: fallback()
        } catch (_: Exception) {
            fallback()
        }
    }

    // ── Fallback prompts (used when resources are unavailable) ────────

    private val RESEARCH_FALLBACK: String = """
You are researching a software project to gather information for creating a Scoop manifest. Use the research tools to gather project info, identify Windows assets, and report findings.

${PhaseSchemas.RESEARCH_SCHEMA}

NEVER fabricate URLs. Use tools to gather real data. If no Windows assets exist, set status to "no_windows_assets".
"""

    private val ANALYZE_FALLBACK: String = """
You are analyzing Windows release assets to prepare data for a Scoop manifest. Compute hashes, inspect archives, and report findings.

${PhaseSchemas.ANALYZE_SCHEMA}

NEVER fabricate hashes. Use computeSha256FromUrl to compute real values.
"""

    private val GENERATE_FALLBACK: String = """
You are generating a complete Scoop manifest for a Windows package.

${ReferenceManifests.ALL}

Build the manifest, call validateManifestJson, then call writeManifestToFile. NEVER fabricate hashes or URLs.
"""
}
