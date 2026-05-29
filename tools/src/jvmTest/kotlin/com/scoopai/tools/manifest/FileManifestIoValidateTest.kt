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
}
