package com.scoopai.core.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Phase status enums — deterministic routing, no magic strings
// ---------------------------------------------------------------------------

/** Research phase status — determines graph routing after the LLM finishes. */
@Serializable
enum class ResearchStatus {
    @SerialName("ok") OK,
    @SerialName("no_windows_assets") NO_WINDOWS_ASSETS,
}

/** Analyze phase status. */
@Serializable
enum class AnalyzeStatus {
    @SerialName("ok") OK,
    @SerialName("needs_clarification") NEEDS_CLARIFICATION,
}

/**
 * Structured output for the Research phase.
 *
 * Instead of free-text summaries, the research agent must produce a JSON
 * conforming to this schema so that downstream phases can parse it reliably.
 */
@Serializable
data class ResearchOutput(
    /** Routing status — determines downstream graph path. */
    val status: ResearchStatus = ResearchStatus.OK,
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
    /** Suggested kebab-case app name reflecting variant (e.g. llama-cpp-sycl) */
    val suggested_app_name: String? = null,
    /** Config/data directories or files found in README/docs that should be persisted, e.g. ["config", ".env"] */
    val persist_targets: List<String> = emptyList(),
    /** Env vars that override config paths discovered from source, e.g. {"BAT_CONFIG_DIR": "config"} */
    val env_targets: Map<String, String> = emptyMap(),
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
    /** Routing status for the analyze phase. */
    val status: AnalyzeStatus = AnalyzeStatus.OK,
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
    /** Environment variables to set, pointing to $dir (e.g. {"BAT_CONFIG_DIR": "$dir"}) */
    val env_set: Map<String, String> = emptyMap(),
    /** "zip", "msi", "innosetup", "nsis", or "" */
    val installer_type: String = "",
    /** Per-architecture installer scripts (NSIS Electron). Key = architecture ("64bit"/"32bit"), value = PS script lines */
    val installer_scripts: Map<String, List<String>> = emptyMap(),
    /** Free-text notes for the generate phase */
    val notes: String = "",
    /** Passed through from Research phase; suggested kebab-case app name */
    val suggested_app_name: String? = null,
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
