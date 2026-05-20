package com.scoopai.core.agent

import kotlinx.serialization.Serializable

/**
 * Structured output for the Research phase.
 *
 * Instead of free-text summaries, the research agent must produce a JSON
 * conforming to this schema so that downstream phases can parse it reliably.
 */
@Serializable
data class ResearchOutput(
    /** "ok" or "no_windows_assets" */
    val status: String = "ok",
    /** "owner/repo" for GitHub projects, or project name */
    val project: String = "",
    val description: String = "",
    val homepage: String = "",
    val license: LicenseInfo? = null,
    /** Latest version string (no leading "v") */
    val version: String = "",
    /** Windows assets discovered during research */
    val windows_assets: List<WindowsAsset> = emptyList(),
    /** Free-text notes for the next phase */
    val notes: String = "",
    /** Only used when status = "no_windows_assets" */
    val alternatives: String = "",
)

@Serializable
data class LicenseInfo(
    /** SPDX identifier, e.g. "MIT", "GPL-3.0-or-later", "Apache-2.0" */
    val value: String,
    /** Where the license was read from: "GitHub API", "LICENSE file", "Cargo.toml", etc. */
    val source: String = "",
)

@Serializable
data class WindowsAsset(
    val url: String,
    val filename: String = "",
    /** "64bit", "32bit", "arm64", or "" if unknown */
    val architecture: String = "",
    /** "zip", "exe", "msi", or "" if unknown */
    val asset_type: String = "",
    /** "release_api", "official_page", "derived" */
    val source: String = "",
)

/**
 * Structured output for the Analyze phase.
 *
 * The analyze agent produces this JSON so that the generate phase receives
 * machine-readable data instead of free-text summaries.
 */
@Serializable
data class AnalyzeOutput(
    /** "ok" or "needs_clarification" */
    val status: String = "ok",
    /** "portable_zip", "installer", "portable_exe" */
    val strategy: String = "",
    /** Per-architecture asset entries with hash */
    val entries: List<AssetEntry> = emptyList(),
    /** Executable(s) to add to PATH. Each item is either a string ("app.exe") or an array (["app.exe", "alias"]). */
    val bin: List<String> = emptyList(),
    /** Directories/files to persist */
    val persist: List<String> = emptyList(),
    /** Shortcut definitions: [target, name] or [target, name, args...] */
    val shortcuts: List<List<String>> = emptyList(),
    /** pre_install script lines */
    val pre_install: List<String> = emptyList(),
    /** "zip", "msi", "innosetup", "nsis", or "" */
    val installer_type: String = "",
    /** Free-text notes for the generate phase */
    val notes: String = "",
)

@Serializable
data class AssetEntry(
    /** "64bit", "32bit", "arm64" */
    val architecture: String,
    val url: String,
    /** SHA-256 hash */
    val hash: String,
    val filename: String = "",
    /** Top-level directory inside the archive, empty string if none */
    val extract_dir: String = "",
)

// ---------------------------------------------------------------------------
// JSON schema descriptions embedded into prompts
// ---------------------------------------------------------------------------

object PhaseSchemas {

    val RESEARCH_SCHEMA: String = """
When you have finished researching, call the `finalize_task_result` tool with your findings as the `result` parameter. The result must be a JSON object conforming to this schema:

{
  "status": "ok" or "no_windows_assets",
  "project": "owner/repo or project name",
  "description": "Brief project description",
  "homepage": "Project homepage URL",
  "license": {
    "value": "SPDX identifier (e.g. MIT, GPL-3.0-or-later, Apache-2.0)",
    "source": "Where you found it (GitHub API, LICENSE file, Cargo.toml, etc.)"
  },
  "version": "Latest version without leading v",
  "windows_assets": [
    {
      "url": "Direct download URL",
      "filename": "Filename on disk",
      "architecture": "64bit or 32bit or arm64",
      "asset_type": "zip or exe or msi",
      "source": "release_api or official_page or derived"
    }
  ],
  "notes": "Any additional context for the analyze phase",
  "alternatives": "Only when status=no_windows_assets: suggested alternatives"
}

Rules:
- If no Windows assets exist, set status to "no_windows_assets" and fill "alternatives" with suggestions.
- The license.value MUST be the most precise SPDX identifier. Use -only or -or-later suffix when the license text indicates it.
- The version MUST NOT have a leading "v".
- All fields except status, project, version are optional (omit or use empty string/list).
- ONLY CALL TOOLS. Do NOT output text responses. When done, call finalize_task_result.
""".trimIndent()

    val ANALYZE_SCHEMA: String = """
When you have finished analyzing, call the `finalize_task_result` tool with your findings as the `result` parameter. The result must be a JSON object conforming to this schema:

{
  "status": "ok" or "needs_clarification",
  "strategy": "portable_zip or installer or portable_exe",
  "entries": [
    {
      "architecture": "64bit or 32bit or arm64",
      "url": "Download URL",
      "hash": "SHA-256 hash (64 hex chars)",
      "filename": "Filename",
      "extract_dir": "Top-level directory inside this archive (string, not object)"
    }
  ],
  "bin": ["app.exe", ["app2.exe", "alias"]],
  "persist": ["config", "data"],
  "shortcuts": [["app.exe", "App Name"]],
  "pre_install": ["script line 1", "script line 2"],
  "installer_type": "zip or msi or innosetup or nsis",
  "notes": "Any additional context for the generate phase"
}

Rules:
- entries[].hash MUST be computed via computeSha256FromUrl, NEVER fabricated.
- bin should only contain user-facing executables, NOT helper/proxy/internal tools.
- persist should only contain paths that actually exist in the archive.
- shortcuts format: [executable, shortcut_name] or [executable, shortcut_name, start_dir].
- pre_install lines are PowerShell statements.
- ONLY CALL TOOLS. Do NOT output text responses. When done, call finalize_task_result.
""".trimIndent()
}
