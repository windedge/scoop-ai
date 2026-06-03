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
    val isMultiArch = analyze.entries.size > 1

    // Check if all multi-arch entries share the same extract_dir
    val sharedExtractDir = if (isMultiArch && analyze.entries.all { it.extract_dir.isNotBlank() } && analyze.entries.map { it.extract_dir }.distinct().size == 1) {
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
                        // Only put extract_dir inside arch block if not shared
                        if (sharedExtractDir == null && entry.extract_dir.isNotBlank()) {
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
                        // Per-arch installer scripts (NSIS Electron)
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
            // Lift shared extract_dir to top level
            if (sharedExtractDir != null) {
                put("extract_dir", sharedExtractDir)
            }
        } else {
            analyze.entries.firstOrNull()?.let { entry ->
                put("url", entry.url)
                put("hash", entry.hash)
                if (entry.extract_dir.isNotBlank()) put("extract_dir", entry.extract_dir)
            }
        }

        // Versioned bin: skip top-level bin (will be inside arch blocks)
        if (effectiveBin.isNotEmpty() && !analyze.versioned_bin) {
            putJsonArray("bin") {
                effectiveBin.forEach { add(JsonPrimitive(it)) }
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
        if (analyze.pre_install.isNotEmpty()) {
            putJsonArray("pre_install") {
                analyze.pre_install.forEach { add(JsonPrimitive(it)) }
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
 * Generates the autoupdate `architecture` block from asset entries and version.
 *
 * For each entry, replaces concrete version strings in `url` and `extract_dir`
 * with `$version`. The output always uses an `architecture` wrapper regardless
 * of whether the skeleton is flat or multi-arch — this matches Scoop's autoupdate
 * format requirements.
 */
internal fun generateAutoupdateArchitectureBlock(
    entries: List<AssetEntry>,
    version: String,
    versionedBin: Boolean = false,
    binAliases: List<String> = emptyList()
): JsonObject {
    // Single entry: flat { "url": "..." } instead of { "architecture": { "64bit": { "url": "..." } } }
    if (entries.size == 1) {
        val entry = entries.first()
        return buildJsonObject {
            put("url", replaceVersionWithTemplate(entry.url, version))
            if (entry.extract_dir.isNotBlank()) {
                put("extract_dir", replaceVersionWithTemplate(entry.extract_dir, version))
            }
            // Versioned bin in autoupdate
            if (versionedBin && binAliases.isNotEmpty()) {
                putJsonArray("bin") {
                    binAliases.forEach { alias ->
                        addJsonArray {
                            add(JsonPrimitive(replaceVersionWithTemplate(entry.filename.removeSuffix(".zip"), version)))
                            add(JsonPrimitive(alias))
                        }
                    }
                }
            }
        }
    }

    // Multi-entry: architecture wrapper
    return buildJsonObject {
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
    val newArchEntries = mutableMapOf<String, JsonElement>()

    for ((archKey, archValue) in archMap) {
        val archEntry = (archValue as? JsonObject)?.toMutableMap() ?: continue
        val url = archEntry["url"]?.let { (it as? JsonPrimitive)?.content } ?: continue

        when (route) {
            InstallerRoute.ZIP, InstallerRoute.INNOSETUP -> {}
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
        }
        newArchEntries[archKey] = JsonObject(archEntry)
    }

    return JsonObject(manifest.toMutableMap().apply {
        put("architecture", JsonObject(newArchEntries))
    })
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
    val url = json["url"]?.jsonPrimitive?.content
        ?: json["architecture"]?.jsonObject?.get("64bit")?.jsonObject?.get("url")?.jsonPrimitive?.content
        ?: json["architecture"]?.jsonObject?.get("32bit")?.jsonObject?.get("url")?.jsonPrimitive?.content
        ?: return manifestJson

    if (!isPortableArchiveUrl(url)) return manifestJson

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
