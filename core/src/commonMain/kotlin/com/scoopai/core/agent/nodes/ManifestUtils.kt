package com.scoopai.core.agent.nodes

import com.scoopai.core.agent.AssetEntry
import com.scoopai.core.agent.WindowsAsset
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.AnalyzeOutput
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Does NOT add checkver/autoupdate (handled by generate LLM phase).
 */
fun assembleManifestSkeleton(research: ResearchOutput, analyze: AnalyzeOutput): String {
    val effectiveBin = filterExecutables(analyze.bin)

    // ---- Normalize pre_install and lift Squirrel/nupkg scripts from installer_scripts ----
    val allInstallerScripts = analyze.installer_scripts.values.flatten()
    val isSquirrelNupkg = allInstallerScripts.any { it.contains(".nupkg", ignoreCase = true) || it.contains("lib\\net45", ignoreCase = true) }
    val liftedPreInstall = if (isSquirrelNupkg) {
        (analyze.pre_install + allInstallerScripts).distinct()
    } else {
        analyze.pre_install
    }
    val effectivePreInstall = normalizePreInstallScripts(liftedPreInstall)

    // Check if inner archive extraction via Expand-7zipArchive is present.
    // If so, extract_dir must be suppressed because Scoop checks extract_dir before pre_install runs.
    val hasInnerExtraction = isSquirrelNupkg || effectivePreInstall.any {
        it.contains("Expand-7zipArchive", ignoreCase = true) &&
            (it.contains("-ExtractDir", ignoreCase = true) || it.contains(".nupkg", ignoreCase = true))
    }

    // Check if all entries share the same extract_dir (trivially true for single entry)
    val sharedExtractDir = if (!hasInnerExtraction && analyze.entries.all { it.extract_dir.isNotBlank() } && analyze.entries.map { it.extract_dir }.distinct().size == 1) {
        analyze.entries.first().extract_dir
    } else {
        null
    }

    // Pre-generate autoupdate and checkver
    val autoupdateArch = generateAutoupdateArchitectureBlock(analyze.entries, research.version, analyze.versioned_bin, analyze.bin_aliases)
    val checkverValue = generateCheckver(research.homepage, research.project)

    // ---- Auto-fill env_set and persist from research.env_targets ----
    // LLM's explicit analyze.env_set takes priority; if empty, fall back to env_targets.
    val effectiveEnvSet: Map<String, String>
    val envTargetDirs: List<String>
    when {
        analyze.env_set.isNotEmpty() -> {
            effectiveEnvSet = analyze.env_set
            envTargetDirs = emptyList()
        }
        research.env_targets.isNotEmpty() -> {
            // Compute all dirs that will be persisted (explicit analyze.persist + non-blank env_target dirs)
            val allPersistDirs = (analyze.persist + research.env_targets.values.filter { it.isNotBlank() }).distinct()
            effectiveEnvSet = research.env_targets.mapValues { (_, dir) ->
                if (dir.isNotBlank()) {
                    // If this dir is persisted, use $persist_dir to survive updates; otherwise $dir
                    if (dir in allPersistDirs) "\${'$'}persist_dir\\$dir" else "\${'$'}dir\\$dir"
                } else {
                    "\${'$'}dir"
                }
            }
            envTargetDirs = research.env_targets.values.filter { it.isNotBlank() }
        }
        else -> {
            effectiveEnvSet = emptyMap()
            envTargetDirs = emptyList()
        }
    }
    val effectivePersist = if (envTargetDirs.isNotEmpty()) {
        analyze.persist + envTargetDirs.filter { it !in analyze.persist }
    } else {
        analyze.persist
    }

    // ---- Normalize env_set: $dir\x → $persist_dir\x when x is in persist ----
    val normalizedEnvSet = effectiveEnvSet.mapValues { (_, value) ->
        val dirPrefix = "\${'$'}dir\\"
        if (value.startsWith(dirPrefix)) {
            val subdir = value.removePrefix(dirPrefix)
            if (subdir in effectivePersist) "\${'$'}persist_dir\\$subdir" else value
        } else value
    }

    val effectiveDescription = cleanDescription(research.description) ?: research.description

    return buildJsonObject {
        put("version", research.version)
        if (effectiveDescription.isNotBlank()) put("description", effectiveDescription)
        if (research.homepage.isNotBlank()) put("homepage", research.homepage)
        research.license?.value?.let { put("license", it) }

        // Single-entry noarch: use flat format (url/hash at top level)
        // Arch-specific or multi-entry: use architecture wrapper
        val isNoarch = analyze.entries.size == 1 &&
            analyze.entries.first().architecture.isEmpty() &&
            !isArchSpecificUrl(analyze.entries.first().url)
        if (isNoarch) {
            val entry = analyze.entries.first()
            put("url", entry.url)
            put("hash", entry.hash)
        } else {
            putJsonObject("architecture") {
                analyze.entries.forEach { entry ->
                    putJsonObject(entry.architecture) {
                        put("url", entry.url)
                        put("hash", entry.hash)
                        // Only put extract_dir inside arch block if not shared and not inner extraction
                        if (!hasInnerExtraction && sharedExtractDir == null && entry.extract_dir.isNotBlank()) {
                            put("extract_dir", entry.extract_dir)
                        }
                        // Per-arch bin for versioned executables
                        if (analyze.versioned_bin && analyze.bin.isNotEmpty()) {
                            val entryIdx = analyze.entries.indexOf(entry)
                            val versionedExe = analyze.bin.getOrElse(entryIdx) { analyze.bin.first() }
                            val aliases = if (analyze.bin_aliases.isNotEmpty()) analyze.bin_aliases else listOf(versionedExe.removeSuffix(".exe"))
                            putJsonArray("bin") {
                                aliases.forEach { alias ->
                                    addJsonArray {
                                        add(JsonPrimitive(versionedExe))
                                        add(JsonPrimitive(alias))
                                    }
                                }
                            }
                        }
                        // Per-arch installer scripts (only when not lifted to pre_install)
                        if (!isSquirrelNupkg) {
                            val scripts = analyze.installer_scripts[entry.architecture]
                            if (!scripts.isNullOrEmpty()) {
                                putJsonObject("installer") {
                                    putJsonArray("script") {
                                        scripts.forEach { add(JsonPrimitive(fixPowerShellEscaping(it))) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Lift shared extract_dir to top level
        if (sharedExtractDir != null) {
            put("extract_dir", sharedExtractDir)
        }

        // Versioned bin: skip top-level bin (will be inside arch blocks)
        if (effectiveBin.isNotEmpty() && !analyze.versioned_bin) {
            if (effectiveBin.size == 1) {
                put("bin", effectiveBin.first())
            } else {
                putJsonArray("bin") {
                    effectiveBin.forEach { add(JsonPrimitive(it)) }
                }
            }
        }

        if (effectivePersist.isNotEmpty()) {
            putJsonArray("persist") {
                effectivePersist.forEach { add(JsonPrimitive(it)) }
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
        if (effectivePreInstall.isNotEmpty()) {
            putJsonArray("pre_install") {
                effectivePreInstall.forEach { add(JsonPrimitive(it)) }
            }
        }
        if (normalizedEnvSet.isNotEmpty()) {
            putJsonObject("env_set") {
                normalizedEnvSet.forEach { (k, v) -> put(k, v) }
            }
        }
        if (analyze.env_add_path.isNotEmpty()) {
            putJsonArray("env_add_path") {
                analyze.env_add_path.forEach { add(JsonPrimitive(it)) }
            }
        }
        if (analyze.suggest.isNotEmpty()) {
            putJsonObject("suggest") {
                analyze.suggest.forEach { (k, v) -> put(k, v) }
            }
        }

        // Embed pre-generated autoupdate and checkver
        if (checkverValue != null) {
            put("checkver", checkverValue)
        }
        put("autoupdate", autoupdateArch)
    }.toString()
}

// ---------------------------------------------------------------------------
// Autoupdate URL template generation
// ---------------------------------------------------------------------------

/**
 * Replaces all occurrences of [version] in [text] with `$version`.
 *
 * Also tries URL-encoded version (e.g. `%2B` for `+`) so that URL-encoded
 * versions in download URLs are correctly replaced.
 * Returns [text] unchanged if no version match is found.
 */
internal fun replaceVersionWithTemplate(text: String, version: String): String {
    if (version.isBlank()) return text
    // Prefer URL-encoded variant (e.g. %2B for +) when present in the text
    val encoded = runCatching { java.net.URLEncoder.encode(version, "UTF-8") }.getOrNull()
    if (encoded != null && encoded != version && text.contains(encoded)) {
        return text.replace(encoded, "\$version")
    }
    if (text.contains(version)) {
        return text.replace(version, "\$version")
    }
    return text
}

/**
 * Generates the autoupdate block from asset entries and version.
 *
 * For each entry, replaces concrete version strings in `url` and `extract_dir`
 * with `$version`.
 *
 * For **noarch** single-entry manifests (architecture is empty and URL has no
 * arch keywords), produces flat autoupdate (`{"url": "..."}`) — no architecture
 * wrapper. For arch-specific entries, produces the standard architecture wrapper.
 */
internal fun generateAutoupdateArchitectureBlock(
    entries: List<AssetEntry>,
    version: String,
    versionedBin: Boolean = false,
    binAliases: List<String> = emptyList()
): JsonObject {
    val isNoarch = entries.size == 1 &&
        entries.first().architecture.isEmpty() &&
        !isArchSpecificUrl(entries.first().url)

    return buildJsonObject {
        if (isNoarch) {
            // Flat autoupdate for noarch apps
            val entry = entries.first()
            put("url", replaceVersionWithTemplate(entry.url, version))
            if (entry.extract_dir.isNotBlank()) {
                put("extract_dir", replaceVersionWithTemplate(entry.extract_dir, version))
            }
        } else {
            putJsonObject("architecture") {
                entries.forEach { entry ->
                    putJsonObject(entry.architecture) {
                        put("url", replaceVersionWithTemplate(entry.url, version))
                        if (entry.extract_dir.isNotBlank()) {
                            put("extract_dir", replaceVersionWithTemplate(entry.extract_dir, version))
                        }
                        // Versioned bin in autoupdate
                        if (versionedBin && binAliases.isNotEmpty()) {
                            putJsonArray("bin") {
                                val versionedExe = replaceVersionWithTemplate(entry.filename.ifBlank { entry.url.substringAfterLast("/").removeSuffix(".zip") }, version)
                                binAliases.forEach { alias ->
                                    addJsonArray {
                                        add(JsonPrimitive(versionedExe))
                                        add(JsonPrimitive(alias))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Generates a checkver value based on the homepage URL.
 *
 * Returns `JsonPrimitive("github")` for GitHub-hosted projects,
 * or `null` for non-GitHub projects (LLM should determine appropriate checkver).
 */
internal fun generateCheckver(homepage: String, project: String = ""): JsonObject? {
    // Prefer project (owner/repo) to build the full github URL — this works
    // even when homepage is not a GitHub URL (e.g. laragon.org).
    if (project.contains("/")) {
        return buildJsonObject {
            put("github", "https://github.com/$project")
        }
    }
    // Fallback: infer from homepage
    if (homepage.isBlank()) return null
    return if ("github.com" in homepage) {
        buildJsonObject {
            put("github", homepage)
        }
    } else {
        null
    }
}

// ---------------------------------------------------------------------------
// autoupdate URL placeholder check (deterministic verify backstop)
// ---------------------------------------------------------------------------

/**
 * Checks whether all `autoupdate` download URLs carry a version placeholder
 * (such as `$version`, `$matchHead`, ...).
 *
 * Returns an error message describing the first hard-coded URL, or `null` when
 * every URL either uses a placeholder or is a rolling URL (latest/stable/nightly
 * that never changes with the version). This catches manifests whose `version`
 * does not match the release the Windows asset was taken from, which leaves the
 * `autoupdate.url` pinned to a single, concrete version string (e.g. a
 * `preview-<date>-<hash>` tag) so autoupdate silently never updates.
 *
 * Returns `null` (no error) when:
 * - the JSON cannot be parsed,
 * - there is no `autoupdate` field,
 * - `autoupdate` has no `url`.
 */
internal fun checkAutoupdateVersionPlaceholder(manifestJson: String): String? {
    val json = try {
        Json.parseToJsonElement(manifestJson) as? JsonObject ?: return null
    } catch (_: Exception) {
        return null
    }
    val autoupdate = json["autoupdate"] as? JsonObject ?: return null
    val urls = collectAutoupdateUrls(autoupdate)
    for (url in urls) {
        if (!hasVersionVariable(url) && !isRollingUrl(url)) {
            return "autoupdate.url has no \$version placeholder and is not a rolling URL " +
                "(latest/stable/nightly); it is hard-coded to a fixed version so autoupdate " +
                "will not work. Likely the version does not match the release the Windows " +
                "asset belongs to: $url"
        }
    }
    return null
}

/** Collects every `url` inside an `autoupdate` block (flat `url` and per-architecture). */
private fun collectAutoupdateUrls(autoupdate: JsonObject): List<String> {
    val urls = mutableListOf<String>()
    (autoupdate["url"] as? JsonPrimitive)?.content?.let { urls.add(it) }
    (autoupdate["architecture"] as? JsonObject)?.forEach { (_, entry) ->
        (entry as? JsonObject)?.let { archEntry ->
            (archEntry["url"] as? JsonPrimitive)?.content?.let { urls.add(it) }
        }
    }
    return urls
}

/** Scoop autoupdate variables that change with the detected version. */
private val SCOOP_VERSION_VARIABLES = listOf(
    "\$version", "\$matchHead", "\$match1", "\$cleanVersion", "\$dashVersion",
    "\$underscoreVersion", "\$majorVersion", "\$minorVersion", "\$patchVersion",
)

private fun hasVersionVariable(url: String): Boolean =
    SCOOP_VERSION_VARIABLES.any { url.contains(it) }

/**
 * A "rolling" download URL never embeds the version (e.g. `/latest`, `/stable/`,
 * `/nightly`); for these, autoupdate legitimately has no `$version` placeholder.
 */
private fun isRollingUrl(url: String): Boolean {
    val clean = url.substringBefore('#').lowercase()
    return clean.contains("/latest") || clean.contains("/stable/") || clean.contains("/nightly")
}

// ---------------------------------------------------------------------------
// Persist path validation
// ---------------------------------------------------------------------------

/**
 * Validates that all `persist` entries are `$dir`-relative paths.
 *
 * Scoop persist only supports relative paths under `$dir`. The following
 * are INVALID and will be flagged:
 * - Paths starting with `~` (shell tilde, Scoop does not expand it)
 * - Paths starting with `/` or `\` (absolute Unix/Windows paths)
 * - Paths containing `%VAR%` environment variable references
 * - Absolute Windows paths (e.g. `C:\...`)
 *
 * Returns an error message describing the first invalid entry, or `null`
 * when all entries are valid or no `persist` field exists.
 */
internal fun validatePersistPaths(manifestJson: String): String? {
    val json = try {
        Json.parseToJsonElement(manifestJson) as? JsonObject ?: return null
    } catch (_: Exception) {
        return null
    }
    val persist = json["persist"] as? JsonArray ?: return null
    for (element in persist) {
        val path = (element as? JsonPrimitive)?.content ?: continue
        if (path.contains("~") ||
            path.startsWith("/") ||
            path.startsWith("\\") ||
            path.contains("%") ||
            // Absolute Windows path like C:\... or D:\...
            (path.length >= 2 && path[1] == ':' && path[0].isLetter())
        ) {
            return "persist entry '$path' is not a valid \$dir-relative path: " +
                "must be relative (e.g. 'config', 'data'), " +
                "no tilde (~), no environment variables (%%VAR%%), " +
                "no absolute paths"
        }
    }
    return null
}

// ---------------------------------------------------------------------------
// App name conflict detection
// ---------------------------------------------------------------------------

/**
 * Checks whether [appName] conflicts with any existing manifest in local Scoop buckets.
 *
 * Returns an error message if [appName] is present in [usedNames], or `null` if
 * no conflict (including when [appName] is null or blank).
 */
internal fun checkAppNameConflict(appName: String?, usedNames: Set<String>): String? {
    if (appName.isNullOrBlank()) return null
    return if (usedNames.contains(appName)) {
        "App name '$appName' conflicts with an existing manifest in a local Scoop bucket. " +
        "Choose a different name (e.g. prefix with repo name or owner)."
    } else null
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

private val WINDOWS_EXTENSIONS = setOf(".zip", ".exe", ".msi", ".nupkg")
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
 * Derives MSI product/dir name from the main bin entry (e.g. `BOSS.exe` -> `BOSS`).
 * Used as extract_dir fallback for jpackage-style MSI layouts.
 * Does not guess from URL filenames; returns null when bin is empty.
 */
internal fun productNameFromBin(bin: List<String>): String? =
    bin.firstOrNull()
        ?.substringAfterLast('\\')
        ?.substringAfterLast('/')
        ?.removeSuffix(".exe")
        ?.removeSuffix(".EXE")
        ?.takeIf { it.isNotBlank() }

/** True if extract_dir is set at top level or under any architecture entry. */
private fun hasExtractDir(manifest: JsonObject): Boolean {
    if (manifest.containsKey("extract_dir")) return true
    val arch = manifest["architecture"] as? JsonObject ?: return false
    return arch.values.any { (it as? JsonObject)?.containsKey("extract_dir") == true }
}

/**
 * Applies installer-type-specific transformations to a manifest JsonObject.
 *
 * - NSIS / NSIS_ELECTRON: Appends `#/dl.7z` to URL
 * - MSI: Fills missing `extract_dir` from [msiProductName] (per-arch, or top-level if none)
 * - PORTABLE_EXE: Appends `#/<filename>` to URL
 * - ZIP / INNOSETUP: No transformation
 *
 * @param msiProductName Product name for MSI extract_dir fallback (from bin basename).
 *   When null/blank, leave extract_dir unset.
 */
fun applyInstallerTransformations(
    manifest: JsonObject,
    route: InstallerRoute,
    msiProductName: String? = null,
): JsonObject {
    if (route == InstallerRoute.ZIP || route == InstallerRoute.INNOSETUP) return manifest

    val product = msiProductName?.takeIf { it.isNotBlank() }
    val archMap = manifest["architecture"] as? JsonObject
    val hasTopLevelExtractDir = manifest.containsKey("extract_dir")

    val withArch = if (archMap == null) {
        manifest
    } else {
        val newArchEntries = mutableMapOf<String, JsonElement>()
        for ((archKey, archValue) in archMap) {
            val archEntry = (archValue as? JsonObject)?.toMutableMap() ?: continue
            val url = (archEntry["url"] as? JsonPrimitive)?.content ?: continue

            when (route) {
                InstallerRoute.ZIP, InstallerRoute.INNOSETUP -> {}
                InstallerRoute.NSIS, InstallerRoute.NSIS_ELECTRON -> {
                    if (!url.contains("#/")) archEntry["url"] = JsonPrimitive("$url#/dl.7z")
                }
                InstallerRoute.MSI -> {
                    if (!hasTopLevelExtractDir && !archEntry.containsKey("extract_dir") && product != null) {
                        archEntry["extract_dir"] = JsonPrimitive(product)
                    }
                }
                InstallerRoute.PORTABLE_EXE -> {
                    val exeName = url.substringAfterLast("/")
                    if (!url.contains("#/")) archEntry["url"] = JsonPrimitive("$url#/$exeName")
                }
            }
            newArchEntries[archKey] = JsonObject(archEntry)
        }
        JsonObject(manifest.toMutableMap().apply {
            put("architecture", JsonObject(newArchEntries))
        })
    }

    // MSI: if still no extract_dir anywhere (flat format or unfilled arch), lift product to top level
    if (route == InstallerRoute.MSI && product != null && !hasExtractDir(withArch)) {
        return JsonObject(withArch.toMutableMap().apply {
            put("extract_dir", JsonPrimitive(product))
        })
    }
    return withArch
}

/**
 * Post-process skeleton after [assembleManifestSkeleton]: installer-type transforms
 * (MSI extract_dir from bin, NSIS #/dl.7z, portable_exe fragment).
 * No-op for zip/innosetup or invalid JSON.
 */
fun finalizeManifestSkeleton(skeletonJson: String, analyze: AnalyzeOutput): String {
    val route = routeInstallerType(
        type = analyze.installer_type,
        isElectron = analyze.installer_scripts.isNotEmpty(),
    )
    if (route == InstallerRoute.ZIP || route == InstallerRoute.INNOSETUP) return skeletonJson

    val manifest = try {
        Json.parseToJsonElement(skeletonJson).jsonObject
    } catch (_: Exception) {
        return skeletonJson
    }
    return applyInstallerTransformations(manifest, route, productNameFromBin(analyze.bin)).toString()
}

// ---------------------------------------------------------------------------
// PowerShell script fixing
// ---------------------------------------------------------------------------

/**
 * PowerShell variables inside Scoop installer scripts that need backtick escaping.
 *
 * In Scoop manifests, `$dir` and `$original_dir` are substituted by Scoop itself.
 * But `$PLUGINSDIR` and similar NSIS-internal variables are NOT Scoop variables —
 * they are literal directory names inside the extracted archive.
 * Without a backtick, PowerShell treats them as undefined variables and expands
 * them to empty strings, causing "file not found" errors.
 */
private val PS_VARS_TO_ESCAPE = listOf("${'$'}PLUGINSDIR")

/**
 * Fixes common PowerShell escaping issues in Scoop script lines.
 *
 * Specifically, ensures `${'$'}PLUGINSDIR` is escaped as `` `${'$'}PLUGINSDIR ``
 * (backtick-dollar) so PowerShell treats it as a literal string.
 */
fun fixPowerShellEscaping(scriptLine: String): String {
    val target = "${'$'}PLUGINSDIR"
    val escaped = "`${'$'}PLUGINSDIR"
    // If already escaped, do nothing
    if (scriptLine.contains(escaped)) return scriptLine
    return scriptLine.replace(target, escaped)
}

private val detLogger = KotlinLogging.logger {}

/**
 * Appends `#/dl.7z` to MSIX/APPX URLs if not already present.
 * Scoop needs this suffix to treat MSIX as a 7z archive for extraction.
 */
internal fun addMsixDlSuffix(url: String): String {
    if (url.endsWith(".msix") || url.endsWith(".appx")) {
        val fragment = url.substringAfter('#')
        if (fragment.isBlank()) {
            return "$url#/dl.7z"
        }
    }
    return url
}

/**
 * Returns true if the URL points to a portable archive format (not an installer).
 * Strips URL fragment before checking extension.
 */
internal fun isPortableArchiveUrl(url: String): Boolean {
    val withoutFragment = url.substringBefore('#')
    val lower = withoutFragment.lowercase()
    return when {
        lower.endsWith(".exe") || lower.endsWith(".msi") ||
            lower.endsWith(".msix") || lower.endsWith(".appx") -> false
        lower.endsWith(".zip") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ||
            lower.endsWith(".tar.xz") || lower.endsWith(".7z") || lower.endsWith(".tar") ||
            lower.endsWith(".rpm") || lower.endsWith(".deb") -> true
        else -> false
    }
}

/**
 * Safely extracts a single URL string from a JSON element that may be
 * a [JsonPrimitive] (single URL) or [JsonArray] (mirror list, takes first entry).
 * Returns null if the element is absent or has an unexpected type.
 */
private fun getSingleUrl(jsonElement: JsonElement?): String? = when (jsonElement) {
    is JsonPrimitive -> jsonElement.content
    is JsonArray -> jsonElement.firstOrNull()?.jsonPrimitive?.content
    else -> null
}

/**
 * Auto-fills `bin` from `shortcuts` for portable archive manifests.
 * If shortcuts exist but bin is empty/missing, and URL is a portable archive,
 * adds shortcut exe paths as bin entries. Does NOT modify installer-type manifests.
 */
internal fun autoFillBinFromShortcuts(manifestJson: String): String {
    val json = try {
        Json.parseToJsonElement(manifestJson) as? JsonObject ?: return manifestJson
    } catch (_: Exception) {
        return manifestJson
    }

    // Only auto-fill for portable archives
    val url = getSingleUrl(json["url"])
        ?: getSingleUrl(json["architecture"]?.jsonObject?.get("64bit")?.jsonObject?.get("url"))
        ?: getSingleUrl(json["architecture"]?.jsonObject?.get("32bit")?.jsonObject?.get("url"))
        ?: return manifestJson

    // NSIS/InnoSetup with #/dl.7z fragment is also extractable
    val isExtractableInstaller = url.contains("#/dl.7z")
    if (!isPortableArchiveUrl(url) && !isExtractableInstaller) return manifestJson

    // Skip if bin already has entries
    if (json["bin"] != null) return manifestJson

    // Skip if no shortcuts
    val shortcuts = json["shortcuts"]?.jsonArray ?: return manifestJson

    val shortcutExes = shortcuts.mapNotNull {
        (it as? JsonArray)?.getOrNull(0)?.jsonPrimitive?.content
    }
    if (shortcutExes.isEmpty()) return manifestJson

    val binJson = JsonArray(shortcutExes.map { JsonPrimitive(it) })
    val modified = json.toMutableMap().apply {
        put("bin", binJson)
    }
    return JsonObject(modified).toString()
}

/**
 * Post-process manifest JSON: normalize env_set values.
 *
 * If env_set has a value like `$dir\<X>` and `<X>` is in the persist list,
 * replaces it with `$persist_dir\<X>`. This ensures environment variables
 * point to persisted directories that survive updates.
 *
 * Returns the original JSON string unchanged if:
 * - No env_set exists
 * - No values need normalization
 * - The JSON cannot be parsed
 */
internal fun normalizeEnvSet(manifestJson: String): String {
    val json = try {
        Json.parseToJsonElement(manifestJson) as? JsonObject ?: return manifestJson
    } catch (_: Exception) {
        return manifestJson
    }

    val envSet = json["env_set"] as? JsonObject ?: return manifestJson

    val dirPrefix = "\$dir\\"
    val newPersistDirs = mutableListOf<String>()
    var anyReplaced = false

    val newEnvSetEntries = buildMap<String, JsonElement> {
        envSet.forEach { (key, value) ->
            val v = value.jsonPrimitive.content
            if (v.startsWith(dirPrefix)) {
                val subdir = v.removePrefix(dirPrefix)
                put(key, JsonPrimitive("\$persist_dir\\$subdir"))
                newPersistDirs.add(subdir)
                anyReplaced = true
            } else {
                put(key, value)
            }
        }
    }
    if (!anyReplaced) return manifestJson

    // Merge new dirs into persist, avoiding duplicates
    val existingPersist = json["persist"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
    val updatedPersist = buildList {
        addAll(existingPersist)
        for (d in newPersistDirs) {
            if (d !in existingPersist && d !in this) {
                add(d)
            }
        }
    }

    val newJson = json.toMutableMap().apply {
        put("env_set", JsonObject(newEnvSetEntries))
        put("persist", JsonArray(updatedPersist.map { JsonPrimitive(it) }))
    }
    return JsonObject(newJson).toString()
}

// ---------------------------------------------------------------------------
// Architecture wrapping (force flat format into architecture wrapper)
// ---------------------------------------------------------------------------

/**
 * Checks whether a download URL contains architecture-specific keywords.
 *
 * Used to distinguish arch-specific apps (e.g. app-x64.zip) from noarch apps
 * (e.g. audioswitcher-1.8.zip) in `enforceArchitectureWrap` and skeleton assembly.
 *
 * Strips the URL fragment (e.g. `#/dl.7z`) before checking, so the fragment
 * does not interfere with arch detection.
 */
internal fun isArchSpecificUrl(url: String): Boolean {
    val cleanUrl = url.substringBefore('#')
    val lower = cleanUrl.lowercase()
    val archKeywords = listOf(
        "64", "32", "x86", "x64", "arm64", "aarch64",
        "amd64", "i386", "i686", "win64", "win32",
    )
    return archKeywords.any { lower.contains(it) }
}

/**
 * Ensures manifest uses architecture wrapper.
 *
 * If `url`/`hash`/`extract_dir` are at top level (flat format), moves them into
 * `architecture.64bit`. If `architecture` already exists, does nothing.
 *
 * For **noarch** manifests (URL contains no arch keywords like `x64`, `win64`),
 * keeps the flat format — this is the Scoop convention for arch-agnostic apps.
 *
 * Also handles `autoupdate`: if `autoupdate` has flat `url` but no `architecture`,
 * wraps it into `autoupdate.architecture.64bit.url`.
 */
internal fun enforceArchitectureWrap(manifestJson: String): String {
    val json = try {
        Json.parseToJsonElement(manifestJson) as? JsonObject ?: return manifestJson
    } catch (_: Exception) {
        return manifestJson
    }

    // Already has architecture block — nothing to do
    if (json.containsKey("architecture")) return manifestJson

    // No url at top level — nothing to do
    if (!json.containsKey("url")) return manifestJson

    val urlElement = json["url"]
    // Array url (multi-file noarch) — do NOT wrap in architecture
    if (urlElement is JsonArray) return manifestJson

    val url = urlElement?.jsonPrimitive?.content ?: return manifestJson

    // Noarch: URL has no architecture keywords → keep flat format
    if (!isArchSpecificUrl(url)) return manifestJson

    // Move url, hash, extract_dir from top level into architecture.64bit
    val archKeys = listOf("url", "hash", "extract_dir")
    val archBlock = buildJsonObject {
        putJsonObject("64bit") {
            for (key in archKeys) {
                json[key]?.let { put(key, it) }
            }
        }
    }

    // Rebuild manifest without top-level url/hash/extract_dir, add architecture
    val result = buildJsonObject {
        for ((key, value) in json) {
            if (key !in archKeys) {
                put(key, value)
            }
        }
        put("architecture", archBlock)
    }

    // Also handle autoupdate flat format
    val withAutoupdate = processAutoupdateArchitecture(result)

    return withAutoupdate.toString()
}

/**
 * If `autoupdate` has flat `url` but no `architecture`, wraps it into
 * `autoupdate.architecture.64bit.url`.
 */
private fun processAutoupdateArchitecture(manifest: JsonObject): JsonObject {
    val autoupdate = manifest["autoupdate"] as? JsonObject ?: return manifest

    // Already has architecture block — nothing to do
    if (autoupdate.containsKey("architecture")) return manifest

    // No url in autoupdate — nothing to do
    if (!autoupdate.containsKey("url")) return manifest

    // Move autoupdate.url into autoupdate.architecture.64bit.url
    val autoArchBlock = buildJsonObject {
        putJsonObject("64bit") {
            listOf("url", "extract_dir").forEach { key ->
                autoupdate[key]?.let { put(key, it) }
            }
        }
    }

    val newAutoupdate = buildJsonObject {
        for ((key, value) in autoupdate) {
            if (key != "url" && key != "extract_dir") {
                put(key, value)
            }
        }
        put("architecture", autoArchBlock)
    }

    return JsonObject(manifest.toMutableMap().apply {
        put("autoupdate", newAutoupdate)
    })
}

// ---------------------------------------------------------------------------
// Homepage normalization: remove trailing slash
// ---------------------------------------------------------------------------

/**
 * Removes trailing slash from homepage URL if present.
 * Only removes the last character if it is `/`, preserving all internal path slashes.
 * Returns the original JSON unchanged if homepage is missing or already correct.
 */
internal fun normalizeHomepage(manifestJson: String): String {
    val json = try {
        Json.parseToJsonElement(manifestJson) as? JsonObject ?: return manifestJson
    } catch (_: Exception) {
        return manifestJson
    }
    val homepage = json["homepage"] as? JsonPrimitive ?: return manifestJson
    val url = homepage.content
    if (url.endsWith("/") && url.length > 1) {
        val newUrl = url.removeSuffix("/")
        val modified = json.toMutableMap().apply {
            put("homepage", JsonPrimitive(newUrl))
        }
        return JsonObject(modified).toString()
    }
    return manifestJson
}

// ---------------------------------------------------------------------------
// Remove invalid top-level fields from manifest
// ---------------------------------------------------------------------------

private val INVALID_TOP_LEVEL_FIELDS = setOf("installer_type")

/**
 * Removes known-invalid top-level fields from a manifest JSON string.
 *
 * LLMs sometimes hallucinate `installer_type` which is an internal analyze-phase
 * field, not a valid Scoop manifest field. This function removes it.
 *
 * NOTE: `innosetup` is intentionally NOT in this set — it IS a valid Scoop
 * manifest field (see extras bucket with 122+ usages). It tells Scoop to use
 * innounp to automatically extract InnoSetup installers.
 *
 * Returns the original JSON unchanged if no invalid fields are present.
 */
internal fun removeInvalidTopLevelFields(manifestJson: String): String {
    val json = try {
        Json.parseToJsonElement(manifestJson) as? JsonObject ?: return manifestJson
    } catch (_: Exception) {
        return manifestJson
    }
    val hasInvalid = INVALID_TOP_LEVEL_FIELDS.any { json.containsKey(it) }
    if (!hasInvalid) return manifestJson
    val modified = json.toMutableMap().apply {
        INVALID_TOP_LEVEL_FIELDS.forEach { remove(it) }
    }
    return JsonObject(modified).toString()
}

// ---------------------------------------------------------------------------
// Bin normalization: single-element array to string
// ---------------------------------------------------------------------------

/**
 * If `bin` (top-level or inside architecture.*) is a single-element string array
 * like `["app.exe"]`, converts it to a plain string `"app.exe"`.
 *
 * Multi-element arrays (e.g. `["a.exe", "b.exe"]`) and nested arrays
 * (e.g. `[["app.exe", "alias"]]`) are preserved unchanged.
 *
 * Handles both top-level `bin` and architecture sub-level `bin`
 * (e.g. `architecture.64bit.bin`).
 */
internal fun normalizeBin(manifestJson: String): String {
    val json = try {
        Json.parseToJsonElement(manifestJson) as? JsonObject ?: return manifestJson
    } catch (_: Exception) {
        return manifestJson
    }

    var modified = false
    val result = json.toMutableMap()

    // Process top-level bin
    val topBin = result["bin"]
    if (topBin != null) {
        val normalized = normalizeSingleBinElement(topBin)
        if (normalized != null) {
            result["bin"] = normalized
            modified = true
        }
    }

    // Process architecture sub-level bins
    val archElement = result["architecture"]
    if (archElement != null) {
        val arch = (archElement as? JsonObject)?.toMutableMap() ?: return if (modified) JsonObject(result).toString() else manifestJson
        var archModified = false
        for ((archKey, archValue) in arch) {
            val archEntry = (archValue as? JsonObject)?.toMutableMap() ?: continue
            val archBin = archEntry["bin"]
            if (archBin != null) {
                val normalized = normalizeSingleBinElement(archBin)
                if (normalized != null) {
                    archEntry["bin"] = normalized
                    archModified = true
                }
            }
            arch[archKey] = JsonObject(archEntry)
        }
        if (archModified) {
            result["architecture"] = JsonObject(arch)
            modified = true
        }
    }

    return if (modified) JsonObject(result).toString() else manifestJson
}

/**
 * Normalizes a single bin element: if it's a single-element array of primitives,
 * returns the unwrapped primitive. Otherwise returns null (no change).
 */
private fun normalizeSingleBinElement(element: JsonElement): JsonElement? {
    val array = element as? JsonArray ?: return null
    if (array.size != 1) return null
    val first = array[0]
    return first as? JsonPrimitive
}

/** Removes top-level `bin` when architecture-specific `bin` entries exist. */
internal fun resolveDualBinConflicts(manifest: JsonObject): JsonObject {
    val topBin = manifest["bin"] ?: return manifest
    val arch = manifest["architecture"] as? JsonObject ?: return manifest
    val hasArchBin = arch.values.any { (it as? JsonObject)?.containsKey("bin") == true }
    if (!hasArchBin) return manifest
    return JsonObject(manifest.toMutableMap().apply { remove("bin") })
}

private val VERSIONED_NUPKG_REGEX = Regex("""([a-zA-Z0-9_\-\.]+?)-[\d\.]+-full\.nupkg""", RegexOption.IGNORE_CASE)

/**
 * Normalizes pre_install PowerShell script lines:
 * 1. Strips redundant Move-Item and Remove-Item lines for `lib\net45` that trigger runtime errors.
 * 2. Replaces version-pinned `.nupkg` filenames with `*-full.nupkg` to ensure autoupdate resilience.
 * 3. Ensures artifact removal has `-ErrorAction SilentlyContinue`.
 */
internal fun normalizePreInstallScripts(scripts: List<String>): List<String> {
    if (scripts.isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    for (line in scripts) {
        val trimmed = line.trim()
        // Filter out redundant Move-Item and Remove-Item for lib/net45
        if (trimmed.startsWith("Move-Item", ignoreCase = true) && trimmed.contains("lib\\net45", ignoreCase = true)) {
            continue
        }
        if (trimmed.startsWith("Remove-Item", ignoreCase = true) && trimmed.contains("\"\$dir\\lib\"", ignoreCase = true) && !trimmed.contains("RELEASES", ignoreCase = true)) {
            continue
        }

        var normalized = trimmed
        // Replace hardcoded versioned nupkg like Minke-0.3.0-full.nupkg with Minke-*-full.nupkg
        if (normalized.contains("-full.nupkg", ignoreCase = true)) {
            normalized = normalized.replace(VERSIONED_NUPKG_REGEX) { mr ->
                "${mr.groupValues[1]}-*-full.nupkg"
            }
        }
        // Ensure Remove-Item for installer artifacts contains -ErrorAction SilentlyContinue
        if (normalized.startsWith("Remove-Item", ignoreCase = true) && !normalized.contains("-ErrorAction", ignoreCase = true)) {
            normalized = "$normalized -ErrorAction SilentlyContinue"
        }
        result.add(normalized)
    }
    return result
}

private val EMOJI_REGEX = Regex("""[\uD83C-\uDBFF][\uDC00-\uDFFF]|[\u2600-\u27BF\uFE00-\uFE0F\u2300-\u23FF\u2B50\u2B55\u200D]|\p{So}""")
private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)\]\([^\)]+\)""")
private val MARKDOWN_BADGE_REGEX = Regex("""\[!\[[^\]]*\]\([^\)]+\)\]\([^\)]+\)""")
private val LEADING_PUNCTUATION_REGEX = Regex("""^[\s\-:：·•|]+""")
private val WHITESPACE_REGEX = Regex("""\s+""")

/**
 * Cleans a description string:
 * - Strips emojis (e.g. `🐳 `)
 * - Strips markdown badges and link wrappers
 * - Strips leading punctuation and excess spaces
 * - Capitalizes the first letter
 */
internal fun cleanDescription(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val text = raw.trim()
        .replace(MARKDOWN_BADGE_REGEX, "")
        .replace(MARKDOWN_LINK_REGEX, "$1")
        .replace(EMOJI_REGEX, " ")
        .replace(LEADING_PUNCTUATION_REGEX, "")
        .replace(WHITESPACE_REGEX, " ")
        .trim()
    if (text.isBlank()) return null
    return text.replaceFirstChar { it.uppercase() }
}
