package com.scoopai.core.agent.prompt

import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.PhaseSchemas
import com.scoopai.core.agent.ResearchOutput

/**
 * Builds the prompt for the Research phase.
 *
 * @param url The project URL to research.
 * @param outputPath Optional output file path for the manifest.
 */
fun buildResearchPrompt(url: String, outputPath: String?, userPrompt: String? = null): String {
    val task = buildString {
        append("Research the project at: $url")
        if (outputPath != null) append("\nOutput file: $outputPath")
        if (!userPrompt.isNullOrBlank()) append("\n\n## Additional User Requirements\n$userPrompt")
    }
    return """You are now in the RESEARCH phase. Your task is to gather information about the project using research tools (fetchRepoInfo, fetchLatestRelease, fetchAllReleases, fetchWebPage, etc).

$task

Research stopping rule: as soon as you have enough trustworthy Windows asset URLs for the analyze phase to compute hashes, stop researching and report the findings. If GitHub releases have no assets, prefer the official download page and stop once version variables, filename patterns, or direct download links are sufficient to derive the real Windows asset URLs.

IMPORTANT: Use the available tools to gather information. Do not answer from your training data alone. Call the GitHub API and web fetcher tools to get real, up-to-date data.

${PhaseSchemas.RESEARCH_SCHEMA}"""
}

/**
 * Builds the prompt for the Analysis phase.
 *
 * @param research The structured output from the Research phase.
 */
fun buildAnalyzePrompt(research: ResearchOutput, userPrompt: String? = null): String {
    val researchJson = kotlinx.serialization.json.Json.encodeToString(ResearchOutput.serializer(), research)
    val userReq = if (!userPrompt.isNullOrBlank()) "\n\n## Additional User Requirements\n$userPrompt" else ""
    return """You are now in the ANALYSIS phase. Your task is to compute hashes and inspect archive contents using the analysis tools (computeSha256FromUrl, listArchiveContents, findExecutables).

Based on the following research results, analyze only the most likely Scoop manifest assets first.

```json
$researchJson
```

Prefer portable ZIP assets when suitable. Choose one installation strategy first, avoid exhaustive analysis of every candidate, and stop as soon as you have enough data to generate the manifest. Compute SHA-256 hashes only for selected assets using computeSha256FromUrl.
$userReq
For any archive (ZIP, MSI, etc.): use listArchiveContents to list file paths and findExecutables to find .exe files. Both tools auto-detect the archive type from the URL.
For MSI files specifically: the extract_dir field is REQUIRED when files are inside a subdirectory (e.g. "PFiles/FFStudio"). Omitting extract_dir will cause "File doesn't exist" errors at install time.

Only detect installer type if you are actually using an installer-based strategy or ZIP analysis is insufficient.

If the research output contains a `suggested_app_name`, include it unchanged in your analysis output JSON as `suggested_app_name` so the generate phase can use it.

IMPORTANT: You MUST call the analysis tools (computeSha256FromUrl, listArchiveContents, findExecutables) to compute real values. Do not guess or estimate hashes.

${PhaseSchemas.ANALYZE_SCHEMA}"""
}

/**
 * Builds the prompt for the Generate phase.
 *
 * @param analyze The structured output from the Analysis phase.
 * @param outputPath Optional output file path for the manifest.
 */
fun buildGeneratePrompt(analyze: AnalyzeOutput, outputPath: String?, userPrompt: String? = null): String {
    val analyzeJson = kotlinx.serialization.json.Json.encodeToString(AnalyzeOutput.serializer(), analyze)
    val outputPart = if (outputPath != null) "\nOutput file: $outputPath" else ""
    val suggestedAppName = analyze.suggested_app_name?.let { name ->
        if (name.isNotBlank()) name else null
    }
    val suggestedPathGuidance = if (suggestedAppName != null) {
        """

## Suggested App Name

The analysis suggests app name: `$suggestedAppName`.
When calling `writeManifestToFile`, pass `"bucket/$suggestedAppName.json"` as the `path` parameter so the manifest is saved with the correct variant-specific filename.
"""
    } else ""
    val userReq = if (!userPrompt.isNullOrBlank()) """

## Additional User Requirements
$userPrompt

When user requirements specify a particular variant (e.g. SYCL-enabled, CUDA, Vulkan), you MUST:
1. Reflect the variant in the `description` field (e.g. "LLM inference in C/C++ (SYCL-enabled)" instead of just "LLM inference in C/C++").
2. Ensure the chosen assets match the requested variant.""" else ""
    return """You are now in the GENERATION phase. Your task is to generate a Scoop manifest JSON and save it using the tools (validate, write).

Based on the following analysis JSON, generate a complete Scoop manifest.

## Analysis JSON
```json
$analyzeJson
```
$outputPart$suggestedPathGuidance$userReq

You MUST call the tools: first call validate with the manifest JSON to check it, then call write to save it.
After writing the manifest, call `finalize_task_result` with the final manifest JSON as the `result` parameter.
Do NOT output the manifest as plain text — only use tools.

Scoop manifest rules:
- `extract_dir`: ONLY include this field when the executable is inside a NAMED subdirectory (e.g. "fd-v10.2.0-x86_64-pc-windows-msvc" for ZIP, or "PFiles\\FFStudio" for MSI). If the exe is at the archive root, OMIT `extract_dir` entirely — do NOT use `"."` or `""`. MSI files almost always need `extract_dir` because Scoop extracts them into nested subdirectories.
- Do NOT add fields not recognized by Scoop (e.g. `innosetup`, `installer.script`). Only use standard Scoop manifest fields.
- `bin` should be a string or list of strings/arrays matching actual exe paths inside the archive."""
}
