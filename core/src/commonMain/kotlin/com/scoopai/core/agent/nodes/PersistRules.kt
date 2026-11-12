package com.scoopai.core.agent.nodes

// ---------------------------------------------------------------------------
// Persist hints from deterministic rules
// ---------------------------------------------------------------------------

/**
 * Result of deterministic persist hint inference.
 *
 * @property frameworks Detected frameworks (e.g. "Electron", "Tauri")
 * @property persistTargets Suggested persist target names (e.g. "config", "themes")
 * @property envTargets Env vars that override config paths (e.g. "BAT_CONFIG_DIR" → "config")
 * @property notes Free-text notes for the LLM (e.g. "Electron app: userData at %APPDATA%/app-name")
 */
data class PersistHints(
    val frameworks: List<String> = emptyList(),
    val persistTargets: List<String> = emptyList(),
    val envTargets: Map<String, String> = emptyMap(),
    val notes: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Framework detection
// ---------------------------------------------------------------------------

private val ELECTRON_PATTERNS = listOf(
    "\"electron\"" to "package.json electron dependency",
    "electron-store" to "electron-store usage",
    "app.getPath('userData')" to "Electron userData path",
    "app.getPath(" to "Electron path API",
)

private val TAURI_PATTERNS = listOf(
    "\"identifier\"" to "tauri.conf.json identifier",
    "tauri.conf.json" to "Tauri config file presence",
)

/** Known config directory names that indicate user data should be persisted. */
private val CONFIG_DIR_NAMES = setOf(
    "config", "configs", "configuration",
    "themes", "skins",
    "layouts", "layout",
    "profiles", "profile",
    "plugins", "extensions",
    "data", "Data",
    "settings", "userdata",
    "programdata",
)

/**
 * Env var patterns that indicate config path overrides.
 * Key: regex pattern to search in source, Value: env var name extraction.
 */
private val ENV_VAR_PATTERNS = listOf(
    // *_CONFIG_DIR, *_CONFIG_PATH, *_CONFIG_HOME, *_HOME, *_DATA_DIR
    Regex("""(?:getenv|env::var|os\.Getenv|System\.getenv)\(["']([\w]+_(?:CONFIG_DIR|CONFIG_PATH|CONFIG_HOME|HOME|DATA_DIR))["']\)"""),
    // Direct env var references
    Regex("""process\.env\.(\w+_(?:CONFIG_DIR|CONFIG_PATH|CONFIG_HOME|HOME|DATA_DIR))"""),
    // XDG standard
    Regex("""XDG_CONFIG_HOME"""),
    // Generic APPDATA/LOCALAPPDATA
    Regex("""(?:getenv|env::var|os\.Getenv|System\.getenv)\(["'](APPDATA|LOCALAPPDATA)["']\)"""),
)

/**
 * Infer persist hints from source code file paths and snippets.
 *
 * This is a pure function: no I/O, no network calls. It applies deterministic
 * rules based on:
 * 1. File paths in the source tree (e.g. config/, themes/, tauri.conf.json)
 * 2. Text snippets from key files (e.g. package.json, Cargo.toml, source files)
 *
 * The result is advisory; the analyze phase LLM makes the final call.
 */
fun inferPersistHints(
    filePaths: List<String>,
    fileSnippets: Map<String, String>,
): PersistHints {
    val frameworks = mutableListOf<String>()
    val persistTargets = mutableListOf<String>()
    val envTargets = mutableMapOf<String, String>()
    val notes = mutableListOf<String>()

    val allSnippets = fileSnippets.values.joinToString("\n")
    val allFilePaths = filePaths.joinToString("\n")

    // ---- Framework detection ----

    // Electron
    val isElectron = ELECTRON_PATTERNS.any { (pattern, _) ->
        allSnippets.contains(pattern) || pattern == "\"electron\"" && fileSnippets["package.json"]?.contains("\"electron\"") == true
    }
    if (isElectron) {
        frameworks.add("Electron")
        notes.add("Electron app detected. User data lives at %APPDATA%/<app-name>. The Data/ directory in the archive is Electron-managed internal data and MUST NOT be added to persist_targets.")
    }

    // Tauri
    val isTauri = fileSnippets.keys.any { it.contains("tauri.conf.json") } ||
        allFilePaths.contains("tauri.conf.json") ||
        TAURI_PATTERNS.any { (pattern, _) -> allSnippets.contains(pattern) }
    if (isTauri) {
        frameworks.add("Tauri")
        // Extract identifier from tauri.conf.json
        val identifierMatch = Regex(""""identifier"\s*:\s*"([^"]+)"""").find(allSnippets)
        if (identifierMatch != null) {
            val identifier = identifierMatch.groupValues[1]
            notes.add("Tauri app detected. Config at %APPDATA%/$identifier (from tauri.conf.json identifier).")
        } else {
            notes.add("Tauri app detected. Config at %APPDATA%/<tauri.bundle.identifier>.")
        }
    }

    // ---- Config directory detection from file paths ----

    // Extract top-level directory names from file paths
    val topDirs = filePaths.mapNotNull { path ->
        val parts = path.split("/")
        if (parts.size >= 2) parts[0] else null
    }.toSet()

    for (dir in topDirs) {
        if (dir.lowercase() in CONFIG_DIR_NAMES || dir in CONFIG_DIR_NAMES) {
            // For Data/: only add if NOT Electron (Electron Data/ is internal)
            if (dir == "Data" && isElectron) continue
            if (dir == "data" && isElectron) continue
            if (!persistTargets.contains(dir)) {
                persistTargets.add(dir)
            }
        }
    }

    // ---- Env var detection from snippets ----

    for (pattern in ENV_VAR_PATTERNS) {
        val matches = pattern.findAll(allSnippets)
        for (match in matches) {
            val envVarName = match.groupValues.getOrNull(1) ?: match.value
            if (envVarName == "XDG_CONFIG_HOME") {
                envTargets["XDG_CONFIG_HOME"] = "config"
                notes.add("Source uses XDG_CONFIG_HOME for config directory override.")
            } else if (envVarName == "APPDATA" || envVarName == "LOCALAPPDATA") {
                notes.add("Source uses $envVarName for data storage path.")
            } else {
                // Custom config dir env var (e.g. BAT_CONFIG_DIR)
                val dirName = envVarName.substringAfterLast("_").lowercase()
                    .removeSuffix("_dir").removeSuffix("_path").removeSuffix("_home")
                envTargets[envVarName] = dirName.ifBlank { "config" }
            }
        }
    }

    // ---- Dotfile/config patterns in README ----

    val readmeSnippet = fileSnippets.entries
        .filter { it.key.lowercase().contains("readme") }
        .map { it.value }
        .joinToString("\n")

    if (readmeSnippet.isNotBlank()) {
        // ~/.config/<app>/ pattern
        val xdgPattern = Regex("""~/.config/(\w+)/""")
        val xdgMatches = xdgPattern.findAll(readmeSnippet)
        for (match in xdgMatches) {
            val appName = match.groupValues[1]
            notes.add("README mentions config at ~/.config/$appName/ (XDG convention).")
        }

        // %APPDATA%/<app> pattern
        val appDataPattern = Regex("""%APPDATA%[/\\]([\w.-]+)""")
        val appDataMatches = appDataPattern.findAll(readmeSnippet)
        for (match in appDataMatches) {
            val appName = match.groupValues[1]
            notes.add("README mentions config at %APPDATA%/$appName.")
        }
    }

    // ---- Root-level config file detection ----

    val rootConfigFiles = listOf(
        "config.yaml", "config.yml", "config.toml", "config.json",
        "settings.json", "settings.toml", "app.conf",
        // Extended config formats
        "config.ini", "config.cfg", "config.xml", "config.properties",
        "settings.ini", "settings.cfg", "settings.xml",
        "app.ini", "app.cfg", "app.xml",
    )
    val rootFiles = filePaths.map { it.substringAfterLast("/") }
    for (configFile in rootConfigFiles) {
        if (rootFiles.contains(configFile) && persistTargets.isEmpty()) {
            notes.add("Source contains root-level config file: $configFile. The app likely creates this file at runtime.")
        }
    }

    // ---- Archive-internal config file scanning ----

    val archiveConfigExtensions = setOf(".ini", ".cfg", ".conf", ".xml", ".json")
    val nonConfigStems = setOf("uninstall", "install", "setup", "readme")

    for (path in filePaths) {
        val depth = path.count { it == '/' }
        if (depth !in 1..2) continue // only one or two levels deep

        val filename = path.substringAfterLast("/")
        val dotIndex = filename.lastIndexOf('.')
        if (dotIndex < 0) continue

        val stem = filename.substring(0, dotIndex).lowercase()
        val ext = filename.substring(dotIndex).lowercase()

        if (ext in archiveConfigExtensions && nonConfigStems.none { stem.startsWith(it) }) {
            notes.add("Archive contains config file: $filename. Consider adding to persist.")
        }
    }

    return PersistHints(
        frameworks = frameworks.distinct(),
        persistTargets = persistTargets.distinct(),
        envTargets = envTargets,
        notes = notes.distinct(),
    )
}
