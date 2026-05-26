package com.scoopai.core.agent.nodes

import com.scoopai.core.agent.WindowsAsset
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.AnalyzeOutput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.put

// ---------------------------------------------------------------------------
// Manifest skeleton assembly (deterministic replacement for part of generate)
// ---------------------------------------------------------------------------

/**
 * Assembles a Scoop manifest JSON skeleton from research and analyze metadata.
 *
 * This is deterministic: version/description/homepage/license come from research,
 * url/hash/bin/extract_dir from analyze entries/executables, and optional
 * persist/shortcuts/pre_install/env_set from analyze data.
 *
 * Does NOT add checkver/autoupdate (handled by refine LLM phase).
 */
fun assembleManifestSkeleton(research: ResearchOutput, analyze: AnalyzeOutput): String {
    val effectiveBin = filterExecutables(analyze.bin)
    val isMultiArch = analyze.entries.size > 1

    return buildJsonObject {
        put("version", research.version)
        if (research.description.isNotBlank()) put("description", research.description)
        if (research.homepage.isNotBlank()) put("homepage", research.homepage)
        research.license?.value?.let { put("license", it) }

        if (isMultiArch) {
            putJsonObject("architecture") {
                analyze.entries.forEach { entry ->
                    putJsonObject(entry.architecture) {
                        put("url", entry.url)
                        put("hash", entry.hash)
                        if (entry.extract_dir.isNotBlank()) put("extract_dir", entry.extract_dir)
                    }
                }
            }
        } else {
            analyze.entries.firstOrNull()?.let { entry ->
                put("url", entry.url)
                put("hash", entry.hash)
                if (entry.extract_dir.isNotBlank()) put("extract_dir", entry.extract_dir)
            }
        }

        if (effectiveBin.isNotEmpty()) {
            putJsonArray("bin") {
                effectiveBin.forEach { add(JsonPrimitive(it)) }
            }
        }

        if (analyze.persist.isNotEmpty()) {
            putJsonArray("persist") {
                analyze.persist.forEach { add(JsonPrimitive(it)) }
            }
        }
        if (analyze.shortcuts.isNotEmpty()) {
            putJsonArray("shortcuts") {
                analyze.shortcuts.forEach { shortcut ->
                    addJsonArray {
                        shortcut.forEach { add(JsonPrimitive(it)) }
                    }
                }
            }
        }
        if (analyze.pre_install.isNotEmpty()) {
            putJsonArray("pre_install") {
                analyze.pre_install.forEach { add(JsonPrimitive(it)) }
            }
        }
        if (analyze.env_set.isNotEmpty()) {
            putJsonObject("env_set") {
                analyze.env_set.forEach { (k, v) -> put(k, v) }
            }
        }
    }.toString()
}

// ---------------------------------------------------------------------------
// Raw asset info from release API (before filtering)
// ---------------------------------------------------------------------------

/**
 * Represents a raw asset from a release API before Windows-specific filtering.
 */
data class RawAssetInfo(
    val url: String,
    val filename: String,
)

// ---------------------------------------------------------------------------
// Installer route enum
// ---------------------------------------------------------------------------

/**
 * The type of installer handling needed for a manifest asset.
 */
enum class InstallerRoute {
    ZIP,
    MSI,
    INNOSETUP,
    NSIS,
    NSIS_ELECTRON,
    PORTABLE_EXE,
}

// ---------------------------------------------------------------------------
// Architecture detection
// ---------------------------------------------------------------------------

/**
 * Detects CPU architecture from a filename.
 *
 * @return "64bit", "32bit", "arm64", or "" if unknown.
 */
fun detectArchitecture(filename: String): String {
    val lower = filename.lowercase()
    return when {
        lower.contains("x86_64") || lower.contains("x64") ||
            lower.contains("amd64") || lower.contains("win64") -> "64bit"
        lower.contains("x86") || lower.contains("i686") ||
            lower.contains("win32") || lower.contains("i386") -> "32bit"
        lower.contains("arm64") || lower.contains("aarch64") -> "arm64"
        else -> ""
    }
}

// ---------------------------------------------------------------------------
// Windows asset filtering
// ---------------------------------------------------------------------------

private val WINDOWS_EXTENSIONS = setOf(".zip", ".exe", ".msi")
private val EXCLUDED_EXTENSIONS = setOf(".sha256", ".sig", ".asc", ".pem")
private val EXCLUDED_PLATFORM_KEYWORDS = listOf("darwin", "linux", "macos")

/**
 * Filters raw release assets to only include Windows-compatible ones.
 *
 * Excludes:
 * - Non-Windows platforms (darwin, linux, macos)
 * - Signature/checksum files (.sha256, .sig, .asc, .pem)
 * - Source archives (source/src + .tar.gz)
 */
fun filterWindowsAssets(assets: List<RawAssetInfo>): List<WindowsAsset> {
    return assets.mapNotNull { asset ->
        val lower = asset.filename.lowercase()
        val extension = when {
            EXCLUDED_PLATFORM_KEYWORDS.any(lower::contains) -> null
            EXCLUDED_EXTENSIONS.any(lower::endsWith) -> null
            (lower.contains("source") || lower.contains(".src")) && lower.contains(".tar.gz") -> null
            else -> WINDOWS_EXTENSIONS.find(lower::endsWith)
        } ?: return@mapNotNull null

        WindowsAsset(
            url = asset.url,
            filename = asset.filename,
            architecture = detectArchitecture(asset.filename),
            asset_type = extension.removePrefix("."),
            source = "release_api",
        )
    }
}

// ---------------------------------------------------------------------------
// Asset prioritization
// ---------------------------------------------------------------------------

/**
 * Prioritizes Windows assets by variant quality.
 *
 * Priority order:
 * 1. "all" or unified variants over provider-specific
 * 2. "full-portable" over "portable"
 * 3. Desktop/GUI variants over CLI (when project is GUI)
 */
fun prioritizeAssets(assets: List<WindowsAsset>): List<WindowsAsset> {
    return assets.sortedBy { asset ->
        val lower = asset.filename.lowercase()
        var score = 0
        if (!lower.contains("-all-") && !lower.contains("-all.")) score += 10
        if (!lower.contains("full-portable")) score += 5
        score
    }
}

// ---------------------------------------------------------------------------
// Executable filtering
// ---------------------------------------------------------------------------

private val EXCLUDED_EXE_KEYWORDS = listOf(
    "helper", "proxy", "updater", "update", "uninstall", "setup",
    "crash", "service", "broker", "elevat", "patch", "pluginproxy",
)

/**
 * Filters a list of executables to only include user-facing ones.
 *
 * Excludes helper/proxy/updater/crash/service tools.
 */
fun filterExecutables(exes: List<String>): List<String> {
    return exes.filterNot { exe ->
        val lower = exe.lowercase()
        EXCLUDED_EXE_KEYWORDS.any(lower::contains)
    }
}

// ---------------------------------------------------------------------------
// Alias detection
// ---------------------------------------------------------------------------

/**
 * Determines whether an executable needs an alias.
 *
 * An alias is needed when the exe name is not a clean, intuitive command name:
 * - Longer than 20 characters
 * - Contains version numbers (digits separated by dots)
 * - Contains platform prefixes (windows, linux, darwin, amd64, x86_64, etc.)
 * - Contains underscores (not standard command names)
 */
private val VERSION_PATTERN = Regex("""\d+\.\d+""")
private val PLATFORM_KEYWORDS = listOf(
    "windows", "linux", "darwin", "amd64", "x86_64", "i686", "mingw", "msvc", "win32", "win64",
)

fun needsAlias(exeName: String): Boolean {
    val name = exeName.removeSuffix(".exe")
    val lowerName = name.lowercase()
    return name.length > 20 ||
        name.contains('_') ||
        VERSION_PATTERN.containsMatchIn(name) ||
        PLATFORM_KEYWORDS.any(lowerName::contains)
}

// ---------------------------------------------------------------------------
// Installer type routing
// ---------------------------------------------------------------------------

/**
 * Routes the installer type based on detected type and whether the app is Electron.
 */
fun routeInstallerType(type: String, isElectron: Boolean): InstallerRoute = when (type.lowercase()) {
    "msi" -> InstallerRoute.MSI
    "innosetup" -> InstallerRoute.INNOSETUP
    "nsis" -> if (isElectron) InstallerRoute.NSIS_ELECTRON else InstallerRoute.NSIS
    "portable_exe" -> InstallerRoute.PORTABLE_EXE
    else -> InstallerRoute.ZIP
}

// ---------------------------------------------------------------------------
// Installer transformations
// ---------------------------------------------------------------------------

/**
 * Applies installer-type-specific transformations to a manifest JsonObject.
 *
 * - NSIS: Appends `#/dl.7z` to URL
 * - MSI: Adds `extract_dir` with backslashes
 * - PORTABLE_EXE: Appends `#/app.exe` to URL
 * - NSIS_ELECTRON: Adds two-layer extraction pre_install
 * - ZIP: No transformation
 */
fun applyInstallerTransformations(manifest: JsonObject, route: InstallerRoute): JsonObject {
    if (route == InstallerRoute.ZIP || route == InstallerRoute.INNOSETUP) return manifest

    val archMap = manifest["architecture"] as? JsonObject ?: return manifest
    val newArchEntries = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()

    for ((archKey, archValue) in archMap) {
        val archEntry = (archValue as? JsonObject)?.toMutableMap() ?: continue
        val url = archEntry["url"]?.let { (it as? JsonPrimitive)?.content } ?: continue

        when (route) {
            InstallerRoute.NSIS, InstallerRoute.NSIS_ELECTRON -> {
                if (!url.contains("#/")) archEntry["url"] = JsonPrimitive("$url#/dl.7z")
            }
            InstallerRoute.MSI -> {
                if (!archEntry.containsKey("extract_dir")) {
                    val baseName = url.substringAfterLast("/").removeSuffix(".msi")
                    archEntry["extract_dir"] = JsonPrimitive("PFiles\\${baseName.replace("-", "")}")
                }
            }
            InstallerRoute.PORTABLE_EXE -> {
                val exeName = url.substringAfterLast("/")
                if (!url.contains("#/")) archEntry["url"] = JsonPrimitive("$url#/$exeName")
            }
            else -> {}
        }
        newArchEntries[archKey] = JsonObject(archEntry)
    }

    return JsonObject(manifest.toMutableMap().apply {
        put("architecture", JsonObject(newArchEntries))
    })
}
