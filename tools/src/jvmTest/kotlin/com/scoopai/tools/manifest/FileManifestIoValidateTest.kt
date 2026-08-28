package com.scoopai.tools.manifest

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class FileManifestIoValidateTest {

    private val io = FileManifestIo()
    private val D = "${'$'}"  // dollar sign literal

    // ---------------------------------------------------------------
    // validatePreInstallRename: pre_install + Rename-Item for versioned exe
    // ---------------------------------------------------------------

    @Test
    fun `validate flags pre_install Rename-Item for versioned exe`() {
        val manifest = """
        {
            "version": "3.1.0",
            "description": "Test app",
            "homepage": "https://example.com",
            "license": "MIT",
            "architecture": {
                "64bit": {
                    "url": "https://example.com/app-3.1.0-x86_64.exe.zip",
                    "hash": "abc123"
                },
                "32bit": {
                    "url": "https://example.com/app-3.1.0-i686.exe.zip",
                    "hash": "def456"
                }
            },
            "bin": "app.exe",
            "pre_install": "Get-ChildItem \"${D}dir\\app-*.exe\" | Rename-Item -NewName 'app.exe'",
            "checkver": "github",
            "autoupdate": {
                "architecture": {
                    "64bit": {
                        "url": "https://example.com/app-${D}version-x86_64.exe.zip"
                    }
                }
            }
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertTrue(
            result.contains("pre_install") && result.contains("Rename-Item") && result.contains("versioned"),
            "Should flag pre_install Rename-Item for versioned exe: $result"
        )
    }

    @Test
    fun `validate allows pre_install that does not rename versioned exe`() {
        val manifest = """
        {
            "version": "1.0",
            "description": "Test app",
            "homepage": "https://example.com",
            "license": "MIT",
            "url": "https://example.com/app-1.0.zip",
            "hash": "abc123",
            "bin": "app.exe",
            "pre_install": "if (!(Test-Path \"${D}persist_dir\\config\")) { New-Item \"${D}dir\\config\" -ItemType Directory | Out-Null }",
            "checkver": "github",
            "autoupdate": {
                "url": "https://example.com/app-${D}version.zip"
            }
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertFalse(
            result.contains("Rename-Item") || result.contains("versioned"),
            "Should NOT flag non-rename pre_install: $result"
        )
    }

    @Test
    fun `validate allows manifest without pre_install`() {
        val manifest = """
        {
            "version": "1.0",
            "description": "Test app",
            "homepage": "https://example.com",
            "license": "MIT",
            "url": "https://example.com/app-1.0.zip",
            "hash": "abc123",
            "bin": "app.exe",
            "checkver": "github",
            "autoupdate": {
                "url": "https://example.com/app-${D}version.zip"
            }
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertFalse(
            result.contains("pre_install") && result.contains("versioned"),
            "Should NOT flag when no pre_install: $result"
        )
    }

    // ---------------------------------------------------------------
    // validateExtractDirPreInstallConflict
    // ---------------------------------------------------------------

    @Test
    fun `validate flags conflict when pre_install uses Expand-7zipArchive and extract_dir is defined`() {
        val manifest = """
        {
            "version": "0.3.0",
            "description": "DeepSeek Harness Desktop client",
            "homepage": "https://github.com/lencx/Minke",
            "license": "Apache-2.0",
            "architecture": {
                "64bit": {
                    "url": "https://github.com/lencx/Minke/releases/download/v0.3.0/Minke-windows-x64.exe#/dl.7z",
                    "hash": "81003d28b1bfe35ea4a5bf13c3f86aa283c75122eace78efdc4c0d587b4f547b"
                }
            },
            "extract_dir": "lib\\net45",
            "bin": "Minke.exe",
            "shortcuts": [["Minke.exe", "Minke"]],
            "pre_install": [
                "Expand-7zipArchive \"${D}dir\\*-full.nupkg\" -ExtractDir 'lib\\net45' -Removal",
                "Remove-Item \"${D}dir\\RELEASES\" -Force -ErrorAction SilentlyContinue"
            ],
            "checkver": "github"
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertTrue(
            result.contains("Conflict") && result.contains("extract_dir") && result.contains("Expand-7zipArchive"),
            "Should flag extract_dir conflict with Expand-7zipArchive pre_install: $result"
        )
    }

    @Test
    fun `validate allows normal manifest with extract_dir and non-extract pre_install`() {
        val manifest = """
        {
            "version": "1.0.0",
            "description": "Test tool",
            "homepage": "https://example.com",
            "license": "MIT",
            "url": "https://example.com/app-1.0.0.zip",
            "hash": "81003d28b1bfe35ea4a5bf13c3f86aa283c75122eace78efdc4c0d587b4f547b",
            "extract_dir": "app-1.0.0",
            "bin": "app.exe",
            "pre_install": "if (!(Test-Path \"${D}dir\\config.ini\")) { Set-Content \"${D}dir\\config.ini\" '' }",
            "checkver": "github"
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertFalse(
            result.contains("Conflict") && result.contains("extract_dir"),
            "Should NOT flag extract_dir when pre_install is just config creation: $result"
        )
    }

    // ---------------------------------------------------------------
    // validateGuiBin: bin references same exe as shortcuts
    // ---------------------------------------------------------------

    @Test
    fun `validate warns when bin same exe as shortcuts`() {
        val manifest = """
        {
            "version": "1.4",
            "description": "Kill programs instantly",
            "homepage": "https://example.com",
            "license": "GPL-3.0-or-later",
            "architecture": {
                "64bit": {
                    "url": "https://example.com/SuperF4-1.4-64bit.zip",
                    "hash": "abc123"
                }
            },
            "bin": ["SuperF4.exe"],
            "shortcuts": [["SuperF4.exe", "SuperF4"]],
            "checkver": "github",
            "autoupdate": {
                "architecture": {
                    "64bit": {
                        "url": "https://example.com/SuperF4-${D}version-64bit.zip"
                    }
                }
            }
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertTrue(
            result.contains("GUI") && result.contains("which is also in shortcuts"),
            "Should warn when bin and shortcuts reference same exe: $result"
        )
    }

    @Test
    fun `validate does not warn when bin differs from shortcuts`() {
        val manifest = """
        {
            "version": "1.0",
            "description": "CLI tool",
            "homepage": "https://example.com",
            "license": "MIT",
            "url": "https://example.com/app-1.0.zip",
            "hash": "abc123",
            "bin": "cli.exe",
            "shortcuts": [["gui.exe", "App"]],
            "checkver": "github",
            "autoupdate": {
                "url": "https://example.com/app-${D}version.zip"
            }
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertFalse(
            result.contains("GUI") && result.contains("which is also in shortcuts"),
            "Should NOT warn when bin and shortcuts differ: $result"
        )
    }

    @Test
    fun `validate flags top-level bin with architecture-specific exe names`() {
        val manifest = """
        {
            "version": "3.1.0",
            "description": "Test app",
            "homepage": "https://example.com",
            "license": "MIT",
            "architecture": {
                "64bit": {
                    "url": "https://example.com/app-3.1.0-x86_64.exe.zip",
                    "hash": "abc123"
                },
                "32bit": {
                    "url": "https://example.com/app-3.1.0-i686.exe.zip",
                    "hash": "def456"
                }
            },
            "bin": [
                ["app-3.1.0-x86_64.exe", "app"],
                ["app-3.1.0-i686.exe", "app"]
            ],
            "checkver": "github",
            "autoupdate": {
                "architecture": {
                    "64bit": {
                        "url": "https://example.com/app-${D}version-x86_64.exe.zip"
                    }
                }
            }
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertTrue(
            result.contains("Top-level bin") && result.contains("architecture block"),
            "Should flag top-level bin with arch-specific exe names: $result"
        )
    }

    @Test
    fun `validate allows top-level bin without arch-specific exe names`() {
        val manifest = """
        {
            "version": "1.0",
            "description": "Test app",
            "homepage": "https://example.com",
            "license": "MIT",
            "architecture": {
                "64bit": {
                    "url": "https://example.com/app-1.0-64.zip",
                    "hash": "abc123"
                },
                "32bit": {
                    "url": "https://example.com/app-1.0-32.zip",
                    "hash": "def456"
                }
            },
            "bin": ["app.exe"],
            "checkver": "github",
            "autoupdate": {
                "architecture": {
                    "64bit": {
                        "url": "https://example.com/app-${D}version-64.zip"
                    }
                }
            }
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertFalse(
            result.contains("Top-level bin"),
            "Should NOT flag top-level bin with clean exe names: $result"
        )
    }

    // ---------------------------------------------------------------
    // Array URL / Hash (top-level array url/hash should not crash)
    // ---------------------------------------------------------------

    @Test
    fun `top-level url as array does not crash and is valid`() {
        // jsign scenario: top-level array url + array hash
        val manifest = """
        {
            "version":"7.4",
            "description":"Jsign",
            "homepage":"https://example.com",
            "license":"Apache-2.0",
            "url":["https://example.com/jsign-7.4.jar#/jsign.jar","https://example.com/jsign.cmd"],
            "hash":["5c3b1e7c3e9b1a5f1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b","5c3b1e7c3e9b1a5f1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b"],
            "bin":"jsign.cmd"
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertFalse(result.contains("Invalid JSON"), "Array URL should not be treated as invalid JSON: $result")
        assertTrue(result.contains("\"valid\":true"), "Array URL manifest should be valid: $result")
    }

    @Test
    fun `top-level url array with bare exe warns but is valid`() {
        val manifest = """
        {
            "version":"1.0",
            "description":"Test portable",
            "homepage":"https://example.com",
            "license":"MIT",
            "url":["https://example.com/x.exe","https://example.com/y.exe"],
            "hash":["5c3b1e7c3e9b1a5f1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b","5c3b1e7c3e9b1a5f1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b"],
            "bin":"x.exe"
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertFalse(result.contains("Invalid JSON"), "Array URL should not crash: $result")
        assertTrue(result.contains("\"valid\":true"), "Array URL manifest should be valid: $result")
        assertTrue(result.contains("exe without a #/ fragment"), "Should warn about bare exe: $result")
    }

    @Test
    fun `top-level hash array accepts valid sha256 list`() {
        val manifest = """
        {
            "version":"1.0",
            "description":"Test",
            "homepage":"https://example.com",
            "license":"MIT",
            "url":["https://example.com/a.zip","https://example.com/b.zip"],
            "hash":["5c3b1e7c3e9b1a5f1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b","5c3b1e7c3e9b1a5f1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b"],
            "bin":"app.exe"
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertFalse(result.contains("Invalid JSON"), "Hash array should not crash: $result")
        assertTrue(result.contains("\"valid\":true"), "Hash array manifest should be valid: $result")
    }

    @Test
    fun `extract_dir as array does not crash`() {
        val manifest = """
        {
            "version":"1.0",
            "description":"Test",
            "homepage":"https://example.com",
            "license":"MIT",
            "url":"https://example.com/app-1.0.zip",
            "hash":"5c3b1e7c3e9b1a5f1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b",
            "extract_dir":["top","inner"],
            "bin":"app.exe"
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertFalse(result.contains("Invalid JSON"), "Array extract_dir should not crash: $result")
        assertTrue(result.contains("\"valid\":true"), "Array extract_dir should be valid: $result")
    }

    @Test
    fun `architecture url as array does not crash`() {
        val manifest = """
        {
            "version":"1.0",
            "description":"Test",
            "homepage":"https://example.com",
            "license":"MIT",
            "architecture":{
                "64bit":{
                    "url":["https://example.com/app-1.0-64.zip"],
                    "hash":"5c3b1e7c3e9b1a5f1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b"
                }
            },
            "bin":"app.exe",
            "checkver":{"github":"https://github.com/test/app"},
            "autoupdate":{
                "architecture":{
                    "64bit":{
                        "url":"https://github.com/test/app/releases/download/v${D}version/app-${D}version-win64.zip"
                    }
                }
            }
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertFalse(result.contains("Invalid JSON"), "Architecture array URL should not crash: $result")
        assertTrue(result.contains("\"valid\":true"), "Architecture array URL manifest should be valid: $result")
    }

    @Test
    fun `autoupdate architecture url as array does not crash v-prefix check`() {
        val manifest = """
        {
            "version":"1.47.2",
            "description":"Test",
            "homepage":"https://example.com",
            "license":"MIT",
            "architecture":{
                "64bit":{
                    "url":"https://example.com/v1.47.2/app-win64.zip",
                    "hash":"5c3b1e7c3e9b1a5f1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b"
                }
            },
            "bin":"app.exe",
            "checkver":{"github":"https://github.com/test/app"},
            "autoupdate":{
                "architecture":{
                    "64bit":{
                        "url":["https://github.com/test/app/releases/download/v${D}version/app-${D}version-win64.zip"]
                    }
                }
            }
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertFalse(result.contains("Invalid JSON"), "Autoupdate array URL should not crash: $result")
        // Should be valid; array autoupdate URL means v-prefix check is skipped (warning-level)
        assertTrue(result.contains("\"valid\":true"), "Autoupdate array URL manifest should be valid: $result")
    }

    @Test
    fun `architecture extract_dir as array does not crash`() {
        val manifest = """
        {
            "version":"1.0",
            "description":"Test",
            "homepage":"https://example.com",
            "license":"MIT",
            "url":"https://example.com/app-1.0.zip",
            "hash":"5c3b1e7c3e9b1a5f1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b1c0a5e3b",
            "architecture":{
                "64bit":{
                    "extract_dir":["dir64"]
                }
            },
            "bin":"app.exe"
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertFalse(result.contains("Invalid JSON"), "Architecture array extract_dir should not crash: $result")
        assertTrue(result.contains("\"valid\":true"), "Architecture array extract_dir should be valid: $result")
    }

    // ---------------------------------------------------------------
    // Missing hash is an error
    // ---------------------------------------------------------------

    @Test
    fun `architecture url without hash is an error`() {
        val manifest = """
        {
            "version": "9.4.7",
            "description": "BOSS",
            "homepage": "https://example.com",
            "license": "Apache-2.0",
            "architecture": {
                "64bit": { "url": "https://example.com/BOSS-9.4.7.msi" }
            },
            "bin": "BOSS.exe"
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertTrue(result.contains("\"valid\":false"), "Missing hash should invalidate: $result")
        assertTrue(result.contains("hash"), "Should report missing hash as error: $result")
    }

    @Test
    fun `top-level url without hash is an error`() {
        val manifest = """
        {
            "version": "1.0.0",
            "description": "Test",
            "homepage": "https://example.com",
            "license": "MIT",
            "url": "https://example.com/app.zip",
            "bin": "app.exe"
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertTrue(result.contains("\"valid\":false"), "Top-level url without hash should be invalid: $result")
        assertTrue(result.contains("hash"), "Should mention hash: $result")
    }

    @Test
    fun `architecture with hash remains valid`() {
        val manifest = """
        {
            "version": "9.4.7",
            "description": "BOSS",
            "homepage": "https://example.com",
            "license": "Apache-2.0",
            "architecture": {
                "64bit": {
                    "url": "https://example.com/BOSS-9.4.7.msi",
                    "hash": "85040d7800049be1d1f3db2178d3294d2deec614a9680d1bdbf26c54243a3e4d"
                }
            },
            "bin": "BOSS.exe"
        }
        """.trimIndent()
        val result = io.validate(manifest)
        assertTrue(result.contains("\"valid\":true"), "Hash present should be valid: $result")
    }
}
