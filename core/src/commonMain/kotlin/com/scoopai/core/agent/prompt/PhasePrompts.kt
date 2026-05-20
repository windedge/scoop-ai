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
fun buildResearchPrompt(url: String, outputPath: String?): String {
    val task = if (outputPath != null) {
        "Research the project at: $url\nOutput file: $outputPath"
    } else {
        "Research the project at: $url"
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
fun buildAnalyzePrompt(research: ResearchOutput): String {
    val researchJson = kotlinx.serialization.json.Json.encodeToString(ResearchOutput.serializer(), research)
    return """You are now in the ANALYSIS phase. Your task is to compute hashes and inspect archive contents using the analysis tools (computeSha256FromUrl, listArchiveContents, findExecutables).

Based on the following research results, analyze only the most likely Scoop manifest assets first.

```json
$researchJson
```

Prefer portable ZIP assets when suitable. Choose one installation strategy first, avoid exhaustive analysis of every candidate, and stop as soon as you have enough data to generate the manifest. Compute SHA-256 hashes only for selected assets using computeSha256FromUrl.

For any archive (ZIP, MSI, etc.): use listArchiveContents to list file paths and findExecutables to find .exe files. Both tools auto-detect the archive type from the URL.
For MSI files specifically: the extract_dir field is REQUIRED when files are inside a subdirectory (e.g. "PFiles/FFStudio"). Omitting extract_dir will cause "File doesn't exist" errors at install time.

Only detect installer type if you are actually using an installer-based strategy or ZIP analysis is insufficient.

IMPORTANT: You MUST call the analysis tools (computeSha256FromUrl, listArchiveContents, findExecutables) to compute real values. Do not guess or estimate hashes.

${PhaseSchemas.ANALYZE_SCHEMA}"""
}

/**
 * Builds the prompt for the Generate phase.
 *
 * @param analyze The structured output from the Analysis phase.
 * @param outputPath Optional output file path for the manifest.
 */
fun buildGeneratePrompt(analyze: AnalyzeOutput, outputPath: String?): String {
    val analyzeJson = kotlinx.serialization.json.Json.encodeToString(AnalyzeOutput.serializer(), analyze)
    val outputPart = if (outputPath != null) "\nOutput file: $outputPath" else ""
    return """You are now in the GENERATION phase. Your task is to generate a Scoop manifest JSON and save it using the tools (validate, write).

Based on the following analysis JSON, generate a complete Scoop manifest.

## Analysis JSON
```json
$analyzeJson
```
$outputPart

You MUST call the tools: first call validate with the manifest JSON to check it, then call write to save it.
After writing the manifest, call `finalize_task_result` with the final manifest JSON as the `result` parameter.
Do NOT output the manifest as plain text — only use tools.

Scoop manifest rules:
- `extract_dir`: ONLY include this field when the executable is inside a NAMED subdirectory (e.g. "fd-v10.2.0-x86_64-pc-windows-msvc" for ZIP, or "PFiles\\FFStudio" for MSI). If the exe is at the archive root, OMIT `extract_dir` entirely — do NOT use `"."` or `""`. MSI files almost always need `extract_dir` because Scoop extracts them into nested subdirectories.
- Do NOT add fields not recognized by Scoop (e.g. `innosetup`, `installer.script`). Only use standard Scoop manifest fields.
- `bin` should be a string or list of strings/arrays matching actual exe paths inside the archive."""
}

/**
 * Builds the prompt for the Fix phase when validation fails.
 *
 * @param originalJson The manifest JSON that failed validation.
 * @param errors The list of validation error messages.
 */
fun buildFixPrompt(originalJson: String, errors: List<String>): String {
    val errorList = errors.joinToString("\n") { "- $it" }
    return """The manifest you generated has validation errors:

$errorList

Here is the original manifest:
$originalJson

Please fix the errors and output the corrected Scoop manifest JSON. Call validateManifestJson and writeManifestToFile."""
}
