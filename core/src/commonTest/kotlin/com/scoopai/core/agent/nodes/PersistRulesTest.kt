package com.scoopai.core.agent.nodes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistRulesTest {

    // ---------------------------------------------------------------
    // Electron detection
    // ---------------------------------------------------------------

    @Test
    fun `Electron app with electron-store in package json yields userData persist`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/main.js", "package.json", "src/store.js"),
            fileSnippets = mapOf(
                "package.json" to """"dependencies": { "electron-store": "^8.0.0", "electron": "^28.0.0" }""",
                "src/main.js" to """app.getPath('userData')""",
            ),
        )
        assertTrue("Electron" in hints.frameworks)
        assertFalse(hints.persistTargets.contains("Data"), "Electron apps should NOT auto-add Data/ as persist (it's Electron-internal)")
        // Instead, the app's userData path is %APPDATA%/<name>, which should be noted
        assertTrue(hints.notes.any { it.contains("userData") || it.contains("APPDATA") })
    }

    @Test
    fun `Electron package json without electron-store still detected as Electron`() {
        val hints = inferPersistHints(
            filePaths = listOf("package.json"),
            fileSnippets = mapOf(
                "package.json" to """"devDependencies": { "electron": "^30.0.0" }""",
            ),
        )
        assertTrue("Electron" in hints.frameworks)
    }

    @Test
    fun `Electron app Data directory should NOT be added to persist`() {
        val hints = inferPersistHints(
            filePaths = listOf("package.json", "Data/config.json"),
            fileSnippets = mapOf(
                "package.json" to """"dependencies": { "electron": "^28.0.0" }""",
            ),
        )
        assertFalse(hints.persistTargets.contains("Data"), "Electron Data/ dir is internal, not user config")
    }

    // ---------------------------------------------------------------
    // Tauri detection
    // ---------------------------------------------------------------

    @Test
    fun `Tauri app with tauri conf json yields identifier-based persist`() {
        val hints = inferPersistHints(
            filePaths = listOf("src-tauri/tauri.conf.json", "src-tauri/Cargo.toml"),
            fileSnippets = mapOf(
                "src-tauri/tauri.conf.json" to """"identifier": "com.example.myapp"""",
            ),
        )
        assertTrue("Tauri" in hints.frameworks)
        // Should note that config is at %APPDATA%/com.example.myapp
        assertTrue(hints.notes.any { it.contains("com.example.myapp") || it.contains("APPDATA") })
    }

    // ---------------------------------------------------------------
    // XDG / env var overrides
    // ---------------------------------------------------------------

    @Test
    fun `source using XDG_CONFIG_HOME yields env target`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/config.rs"),
            fileSnippets = mapOf(
                "src/config.rs" to """std::env::var("XDG_CONFIG_HOME").unwrap_or_else(|_| format!("{}/.config", home_dir))""",
            ),
        )
        assertTrue(hints.envTargets.containsKey("XDG_CONFIG_HOME"))
    }

    @Test
    fun `source using custom env var for config dir yields env target`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/config.go"),
            fileSnippets = mapOf(
                "src/config.go" to """os.Getenv("MYAPP_CONFIG_DIR")""",
            ),
        )
        // Custom *_CONFIG_DIR / *_HOME patterns
        assertTrue(hints.envTargets.containsKey("MYAPP_CONFIG_DIR"))
    }

    @Test
    fun `source using BAT_CONFIG_DIR style env var`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/config.rs"),
            fileSnippets = mapOf(
                "src/config.rs" to """env::var("BAT_CONFIG_DIR")""",
            ),
        )
        assertTrue(hints.envTargets.containsKey("BAT_CONFIG_DIR"))
    }

    // ---------------------------------------------------------------
    // Config file patterns in file names
    // ---------------------------------------------------------------

    @Test
    fun `config yaml file in source yields config persist hint`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/main.rs", "config.yaml", "README.md"),
            fileSnippets = emptyMap(),
        )
        // Should not auto-add "config.yaml" as a persist target from source alone,
        // but should note that config files exist
        assertTrue(hints.persistTargets.isNotEmpty() || hints.notes.isNotEmpty())
    }

    @Test
    fun `known config directories in file paths yield persist hints`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/app.js", "config/default.json", "themes/dark.json"),
            fileSnippets = emptyMap(),
        )
        assertTrue(hints.persistTargets.contains("config"))
        assertTrue(hints.persistTargets.contains("themes"))
    }

    @Test
    fun `profiles directory yields persist hint`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/main.c", "profiles/default.profile"),
            fileSnippets = emptyMap(),
        )
        assertTrue(hints.persistTargets.contains("profiles"))
    }

    @Test
    fun `layouts directory yields persist hint`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/app.rs", "layouts/default.toml"),
            fileSnippets = emptyMap(),
        )
        assertTrue(hints.persistTargets.contains("layouts"))
    }

    // ---------------------------------------------------------------
    // Common config file patterns in snippets
    // ---------------------------------------------------------------

    @Test
    fun `dotfile config pattern in README yields persist hint`() {
        val hints = inferPersistHints(
            filePaths = listOf("README.md"),
            fileSnippets = mapOf(
                "README.md" to """Configuration is stored in `~/.config/myapp/config.toml`""",
            ),
        )
        assertTrue(hints.notes.any { it.contains(".config/myapp") || it.contains("config.toml") })
    }

    @Test
    fun `APPDATA path in source yields persist hint`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/main.c"),
            fileSnippets = mapOf(
                "src/main.c" to """getenv("APPDATA")""",
            ),
        )
        assertTrue(hints.envTargets.containsKey("APPDATA") || hints.notes.any { it.contains("APPDATA") })
    }

    // ---------------------------------------------------------------
    // Negative cases — should not produce false positives
    // ---------------------------------------------------------------

    @Test
    fun `minimal project with no config patterns yields empty hints`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/main.rs", "Cargo.toml"),
            fileSnippets = emptyMap(),
        )
        assertTrue(hints.persistTargets.isEmpty() || hints.persistTargets.none {
            // Common false-positive patterns should not be present
            it == "src" || it == "target" || it == ".git"
        })
    }

    @Test
    fun `Go project with go mod only has no config persist`() {
        val hints = inferPersistHints(
            filePaths = listOf("main.go", "go.mod"),
            fileSnippets = emptyMap(),
        )
        assertTrue(hints.frameworks.none { it == "Electron" || it == "Tauri" })
    }

    @Test
    fun `non-config Data directory in non-Electron project is not special`() {
        val hints = inferPersistHints(
            filePaths = listOf("Data/entries.db", "src/app.c"),
            fileSnippets = emptyMap(),
        )
        // Non-Electron project: Data/ could be user data, should suggest persist
        assertTrue(hints.persistTargets.contains("Data"))
    }

    // ---------------------------------------------------------------
    // Combined scenario
    // ---------------------------------------------------------------

    @Test
    fun `Tauri app with XDG override returns both framework and env`() {
        val hints = inferPersistHints(
            filePaths = listOf("src-tauri/tauri.conf.json", "src/config.rs"),
            fileSnippets = mapOf(
                "src-tauri/tauri.conf.json" to """"identifier": "org.myapp.desktop"""",
                "src/config.rs" to """env::var("XDG_CONFIG_HOME")""",
            ),
        )
        assertTrue("Tauri" in hints.frameworks)
        assertTrue(hints.envTargets.containsKey("XDG_CONFIG_HOME"))
        assertTrue(hints.notes.any { it.contains("org.myapp.desktop") })
    }

    @Test
    fun `Electron app with custom config env var`() {
        val hints = inferPersistHints(
            filePaths = listOf("package.json", "src/main.js"),
            fileSnippets = mapOf(
                "package.json" to """"dependencies": { "electron": "^28.0.0" }""",
                "src/main.js" to """process.env.MYAPP_CONFIG_PATH""",
            ),
        )
        assertTrue("Electron" in hints.frameworks)
        assertTrue(hints.envTargets.containsKey("MYAPP_CONFIG_PATH"))
    }

    // ---------------------------------------------------------------
    // Extended rootConfigFiles detection (.ini, .cfg, .xml, .properties)
    // ---------------------------------------------------------------

    @Test
    fun `config ini at root yields persist hint`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/main.rs", "config.ini"),
            fileSnippets = emptyMap(),
        )
        assertTrue(hints.notes.any { it.contains("config.ini") })
    }

    @Test
    fun `config cfg at root yields persist hint`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/main.rs", "config.cfg"),
            fileSnippets = emptyMap(),
        )
        assertTrue(hints.notes.any { it.contains("config.cfg") })
    }

    @Test
    fun `settings xml at root yields persist hint`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/main.rs", "settings.xml"),
            fileSnippets = emptyMap(),
        )
        assertTrue(hints.notes.any { it.contains("settings.xml") })
    }

    @Test
    fun `config properties at root yields persist hint`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/main.rs", "config.properties"),
            fileSnippets = emptyMap(),
        )
        assertTrue(hints.notes.any { it.contains("config.properties") })
    }

    // ---------------------------------------------------------------
    // Archive-internal config file scanning (.ini, .cfg, .conf, .xml)
    // ---------------------------------------------------------------

    @Test
    fun `ini files in one-level deep subdirectory yield archive config notes`() {
        val hints = inferPersistHints(
            filePaths = listOf("Resources/ColorSchemes.ini", "Resources/Schedules.ini"),
            fileSnippets = emptyMap(),
        )
        assertTrue(hints.notes.any { it.contains("ColorSchemes.ini") })
        assertTrue(hints.notes.any { it.contains("Schedules.ini") })
    }

    @Test
    fun `cfg file in subdirectory yields archive config note`() {
        val hints = inferPersistHints(
            filePaths = listOf("config/app.cfg"),
            fileSnippets = emptyMap(),
        )
        assertTrue(hints.notes.any { it.contains("app.cfg") })
    }

    @Test
    fun `conf file in subdirectory yields archive config note`() {
        val hints = inferPersistHints(
            filePaths = listOf("app/daemon.conf"),
            fileSnippets = emptyMap(),
        )
        assertTrue(hints.notes.any { it.contains("daemon.conf") })
    }

    @Test
    fun `xml file two levels deep yields archive config note`() {
        val hints = inferPersistHints(
            filePaths = listOf("config/tools/settings.xml"),
            fileSnippets = emptyMap(),
        )
        assertTrue(hints.notes.any { it.contains("settings.xml") })
    }

    // ---------------------------------------------------------------
    // Negative cases — archive-internal false positives
    // ---------------------------------------------------------------

    @Test
    fun `uninstall ini in subdirectory is not flagged as config`() {
        val hints = inferPersistHints(
            filePaths = listOf("Resources/uninstall.ini"),
            fileSnippets = emptyMap(),
        )
        assertFalse(hints.notes.any { it.contains("uninstall.ini") })
    }

    @Test
    fun `readme ini in subdirectory is not flagged as config`() {
        val hints = inferPersistHints(
            filePaths = listOf("doc/readme.ini"),
            fileSnippets = emptyMap(),
        )
        assertFalse(hints.notes.any { it.contains("readme.ini") })
    }

    @Test
    fun `ini file more than two levels deep is not scanned`() {
        val hints = inferPersistHints(
            filePaths = listOf("resources/config/themes/colors.ini"),
            fileSnippets = emptyMap(),
        )
        assertFalse(hints.notes.any { it.contains("colors.ini") })
    }

    // ---------------------------------------------------------------
    // Existing functionality regression
    // ---------------------------------------------------------------

    @Test
    fun `config json at root still detected as root config`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/main.rs", "config.json"),
            fileSnippets = emptyMap(),
        )
        assertTrue(hints.notes.any { it.contains("config.json") })
    }

    @Test
    fun `config yaml at root still detected as root config`() {
        val hints = inferPersistHints(
            filePaths = listOf("src/main.rs", "config.yaml"),
            fileSnippets = emptyMap(),
        )
        assertTrue(hints.notes.any { it.contains("config.yaml") })
    }
}
