package com.scoopai.core.agent

object ReferenceManifests {
    val RIPGREP = """{
    "version": "15.1.0",
    "description": "Recursively searches directories for a regex pattern.",
    "homepage": "https://github.com/BurntSushi/ripgrep",
    "license": "MIT",
    "suggest": { "vcredist": "extras/vcredist2022" },
    "architecture": {
        "64bit": {
            "url": "https://github.com/BurntSushi/ripgrep/releases/download/15.1.0/ripgrep-15.1.0-x86_64-pc-windows-msvc.zip",
            "hash": "124510b94b6baa3380d051fdf4650eaa80a302c876d611e9dba0b2e18d87493a",
            "extract_dir": "ripgrep-15.1.0-x86_64-pc-windows-msvc"
        }
    },
    "bin": "rg.exe",
    "checkver": { "github": "https://github.com/BurntSushi/ripgrep" },
    "autoupdate": {
        "architecture": {
            "64bit": {
                "url": "https://github.com/BurntSushi/ripgrep/releases/download/${'$'}version/ripgrep-${'$'}version-x86_64-pc-windows-msvc.zip",
                "extract_dir": "ripgrep-${'$'}version-x86_64-pc-windows-msvc"
            }
        }
    }
}"""

    val LAZYGIT = """{
    "version": "0.61.1",
    "description": "A simple terminal UI for git commands",
    "homepage": "https://github.com/jesseduffield/lazygit",
    "license": "MIT",
    "architecture": {
        "64bit": {
            "url": "https://github.com/jesseduffield/lazygit/releases/download/v0.61.1/lazygit_0.61.1_Windows_x86_64.zip",
            "hash": "6024f3094904caaf9b9672b801cba31a65ad36729a0d2c5a03c432f739c0678b"
        }
    },
    "bin": "lazygit.exe",
    "checkver": { "github": "https://github.com/jesseduffield/lazygit" },
    "autoupdate": {
        "architecture": {
            "64bit": {
                "url": "https://github.com/jesseduffield/lazygit/releases/download/v${'$'}version/lazygit_${'$'}version_Windows_x86_64.zip"
            }
        },
        "hash": { "url": "${'$'}baseurl/checksums.txt" }
    }
}"""

    val SEVEN_ZIP = """{
    "version": "26.01",
    "description": "A multi-format file archiver with high compression ratios.",
    "homepage": "https://www.7-zip.org",
    "license": {
        "identifier": "BSD-2-Clause, BSD-3-Clause, LGPL-2.1-or-later",
        "url": "https://www.7-zip.org/license.txt"
    },
    "architecture": {
        "64bit": {
            "url": "https://github.com/ip7z/7zip/releases/download/26.01/7z2601-x64.msi",
            "hash": "a47ea8dcf8bc08e6de474cae77c828e031fa22cb528f6095defffebf11cd02f2",
            "extract_dir": "Files\\7-Zip"
        }
    },
    "bin": ["7z.exe", "7zG.exe", "7zFM.exe"],
    "shortcuts": [["7zFM.exe", "7-Zip\\7-Zip File Manager"]],
    "persist": ["Codecs", "Formats"],
    "checkver": { "github": "https://github.com/ip7z/7zip" },
    "autoupdate": {
        "architecture": {
            "64bit": {
                "url": "https://github.com/ip7z/7zip/releases/download/${'$'}version/7z${'$'}cleanVersion-x64.msi"
            }
        }
    }
}"""

    val WINMERGE = """{
    "version": "2.16.56",
    "description": "Differencing and merging tool, that compares both folders and files, presenting differences in a visual text format that is easy to understand and handle.",
    "homepage": "https://winmerge.org",
    "license": "GPL-2.0-only",
    "architecture": {
        "64bit": {
            "url": "https://github.com/WinMerge/winmerge/releases/download/v2.16.56/winmerge-2.16.56-x64-exe.zip",
            "hash": "f32de18a5d50f78ff1fc884b1dc6488809a8fcc8f3dcf197fc265a142d5e35c5"
        },
        "32bit": {
            "url": "https://github.com/WinMerge/winmerge/releases/download/v2.16.56/winmerge-2.16.56-exe.zip",
            "hash": "8a4b927f1a07ba59acd736c6f4d4d5a1ab36541d6d22db83c8c7a7c08db4adef"
        },
        "arm64": {
            "url": "https://github.com/WinMerge/winmerge/releases/download/v2.16.56/winmerge-2.16.56-ARM64-exe.zip",
            "hash": "03cb6cf7ae4592f33fe99ee034f583ee02bceaf4b38f5b589561f44c2439417e"
        }
    },
    "extract_dir": "WinMerge",
    "bin": "WinMergeU.exe",
    "shortcuts": [["WinMergeU.exe", "WinMerge"]],
    "checkver": "WinMerge ([\\d.]+) - latest stable version",
    "autoupdate": {
        "architecture": {
            "64bit": {
                "url": "https://github.com/WinMerge/winmerge/releases/download/v${'$'}version/winmerge-${'$'}version-x64-exe.zip"
            },
            "32bit": {
                "url": "https://github.com/WinMerge/winmerge/releases/download/v${'$'}version/winmerge-${'$'}version-exe.zip"
            },
            "arm64": {
                "url": "https://github.com/WinMerge/winmerge/releases/download/v${'$'}version/winmerge-${'$'}version-ARM64-exe.zip"
            }
        },
        "hash": {
            "url": "https://winmerge.org/downloads",
            "regex": "(?sm)<dt>${'$'}basename</dt>.*?<dd><code>${'$'}sha256</code></dd>"
        }
    }
}"""

    val SUMATRAPDF = """{
    "version": "3.6.1",
    "description": "PDF and eBook reader",
    "homepage": "https://www.sumatrapdfreader.org/free-pdf-reader",
    "license": "GPL-3.0-only,BSD-2-Clause",
    "architecture": {
        "64bit": {
            "url": "https://www.sumatrapdfreader.org/dl/rel/3.6.1/SumatraPDF-3.6.1-64.zip",
            "hash": "98b33a518d42986856d225064b0cd2d3643ecf78cbf84ab873d26cc51877a544"
        },
        "32bit": {
            "url": "https://www.sumatrapdfreader.org/dl/rel/3.6.1/SumatraPDF-3.6.1.zip",
            "hash": "670e694a5c91633d28ab0df689b4dbf92021183cfe82270ae552cb617a0a07ed"
        },
        "arm64": {
            "url": "https://www.sumatrapdfreader.org/dl/rel/3.6.1/SumatraPDF-3.6.1-arm64.zip",
            "hash": "f21091ca6741e4049b0ca375121708a7959803d39336f0468084808943667ffe"
        }
    },
    "pre_install": [
        "${'$'}file = 'SumatraPDF-settings.txt'",
        "if (!(Test-Path \"${'$'}persist_dir\\${'$'}file\")) {",
        "    Write-Host 'File' ${'$'}file 'does not exists. Creating.' -f Yellow",
        "    Set-Content \"${'$'}dir\\${'$'}file\" 'CheckForUpdates = false' -Encoding Ascii",
        "}",
        "Get-ChildItem \"${'$'}dir\\SumatraPDF-*.exe\" | Rename-Item -NewName 'SumatraPDF.exe'"
    ],
    "bin": "SumatraPDF.exe",
    "shortcuts": [["SumatraPDF.exe", "SumatraPDF"]],
    "persist": ["SumatraPDF-settings.txt", "sumatrapdfcache"],
    "checkver": {
        "url": "https://www.sumatrapdfreader.org/docs/Version-history",
        "regex": "([\\d.]+)\\s+\\(\\d+"
    },
    "autoupdate": {
        "architecture": {
            "64bit": {
                "url": "https://www.sumatrapdfreader.org/dl/rel/${'$'}version/SumatraPDF-${'$'}version-64.zip"
            },
            "32bit": {
                "url": "https://www.sumatrapdfreader.org/dl/rel/${'$'}version/SumatraPDF-${'$'}version.zip"
            },
            "arm64": {
                "url": "https://www.sumatrapdfreader.org/dl/rel/${'$'}version/SumatraPDF-${'$'}version-arm64.zip"
            }
        }
    }
}"""

    val BAT_CLI = """{
    "version": "0.26.1",
    "description": "A cat(1) clone with syntax highlighting and Git integration",
    "homepage": "https://github.com/sharkdp/bat",
    "license": "Apache-2.0",
    "architecture": {
        "64bit": {
            "url": "https://github.com/sharkdp/bat/releases/download/v0.26.1/bat-v0.26.1-x86_64-pc-windows-msvc.zip",
            "hash": "0f729b4b6f5f28d395c641eacc2e9ff68d0096b85aa0eec344aa62425144b69b",
            "extract_dir": "bat-v0.26.1-x86_64-pc-windows-msvc"
        }
    },
    "env_set": { "BAT_CONFIG_DIR": "${'$'}dir" },
    "pre_install": [
        "if (!(Test-Path \"${'$'}persist_dir\\config\")) {",
        "    Copy-Item -ErrorAction Ignore \"${'$'}env:APPDATA\\bat\\config\" \"${'$'}dir\\config\"",
        "    New-Item -ErrorAction Ignore \"${'$'}dir\\config\" | Out-Null",
        "}",
        "'syntaxes', 'themes' | ForEach-Object {",
        "    if (!(Test-Path \"${'$'}persist_dir\\${'$'}_\")) {",
        "        Copy-Item -ErrorAction Ignore -Recurse \"${'$'}env:APPDATA\\bat\\${'$'}_\" \"${'$'}dir\\${'$'}_\"",
        "    }",
        "}"
    ],
    "bin": "bat.exe",
    "persist": ["config", "syntaxes", "themes"],
    "checkver": { "github": "https://github.com/sharkdp/bat" },
    "autoupdate": {
        "architecture": {
            "64bit": {
                "url": "https://github.com/sharkdp/bat/releases/download/v${'$'}version/bat-v${'$'}version-x86_64-pc-windows-msvc.zip",
                "extract_dir": "bat-v${'$'}version-x86_64-pc-windows-msvc"
            }
        }
    }
}"""

    val PYENV_WIN = """
    {
        "version": "3.1.1",
        "description": "Simple python version management tool for switching between multiple versions of Python.",
        "homepage": "https://pyenv-win.github.io/pyenv-win/",
        "license": "MIT",
        "notes": "You can specify the PYTHON_BUILD_MIRROR_URL environment variable and run 'pyenv update' to speed up downloads.",
        "architecture": {
            "64bit": {
                "url": "https://github.com/pyenv-win/pyenv-win/archive/v3.1.1.zip",
                "hash": "a7481ccaf1f2328702e4a854a65793e95639f40dc63dd89782d160536f6b1ed0",
                "extract_dir": "pyenv-win-3.1.1"
            }
        },
        "pre_install": "if (!(Test-Path \"${'$'}persist_dir\\pyenv-win\\version\")) { New-Item \"${'$'}dir\\pyenv-win\\version\" | Out-Null }",
        "env_add_path": ["pyenv-win\\bin", "pyenv-win\\shims"],
        "env_set": {
            "PYENV": "${'$'}dir\\pyenv-win",
            "PYENV_ROOT": "${'$'}dir\\pyenv-win",
            "PYENV_HOME": "${'$'}dir\\pyenv-win"
        },
        "persist": ["pyenv-win\\version", "pyenv-win\\shims", "pyenv-win\\versions", "pyenv-win\\install_cache"],
        "checkver": { "github": "https://github.com/pyenv-win/pyenv-win" },
        "autoupdate": {
            "architecture": {
                "64bit": {
                    "url": "https://github.com/pyenv-win/pyenv-win/archive/v${'$'}version.zip",
                    "extract_dir": "pyenv-win-${'$'}version"
                }
            }
        }
    }
    """.trimIndent()

    val CARNAC_NUPKG = """
{
    "version": "4.5.583",
    "description": "A keyboard shortcut manager that displays contextual shortcut hints in overlays",
    "homepage": "https://code52.org/carnac/",
    "license": "MIT",
    "architecture": {
        "64bit": {
            "url": "https://github.com/Code52/carnac/releases/download/4.5.583/carnac.core.4.5.583.nupkg",
            "hash": "0e79f22b1b38b2e490b8cca4e15cad89ba29a6f9",
            "extract_dir": "lib\\net45"
        }
    },
    "bin": "carnac.exe",
    "shortcuts": [["carnac.exe", "Carnac"]],
    "persist": "Keymaps",
    "checkver": { "github": "https://github.com/Code52/carnac" },
    "autoupdate": {
        "architecture": {
            "64bit": {
                "url": "https://github.com/Code52/carnac/releases/download/${'$'}version/carnac.core.${'$'}version.nupkg"
            }
        },
        "hash": {
            "url": "${'$'}baseurl/RELEASES",
            "find": "${'$'}basename"
        }
    }
}
""".trimIndent()

    val BLOBSAVER = """{
    "version": "3.6.0",
    "description": "A GUI and CLI app for automatically saving SHSH blobs.",
    "homepage": "https://github.com/airsquared/blobsaver",
    "license": "GPL-3.0-only",
    "architecture": {
        "64bit": {
            "url": "https://github.com/airsquared/blobsaver/releases/download/v3.6.0/blobsaver-3.6.0.exe",
            "hash": "48d45e70d2217046eb5243973f02c5d0bd71daf6ca0ab23cb0470811b81f4c01"
        }
    },
    "innosetup": true,
    "bin": "blobsaver.exe",
    "shortcuts": [["blobsaver.exe", "blobsaver"]],
    "checkver": "github",
    "autoupdate": {
        "architecture": {
            "64bit": {
                "url": "https://github.com/airsquared/blobsaver/releases/download/v${'$'}version/blobsaver-${'$'}version.exe"
            }
        }
    }
}""".trimIndent()

    /**
     * Tauri NSIS installer pattern.
     *
     * Tauri desktop applications ship as NSIS installers (names like `*_x64-setup.exe`).
     * Unlike Electron NSIS installers, Tauri NSIS does NOT have an inner `app-64.7z` that
     * needs secondary extraction. The 7z payload directly contains the executable and DLLs.
     *
     * Correct pattern:
     * - url: append `#/dl.7z` so Scoop extracts the 7z payload from the NSIS installer
     * - pre_install: Remove-Item "$dir\`$*" to clean up $PLUGINSDIR and other NSIS remnants
     * - No second Expand-7zipArchive call (that is Electron-specific)
     * - No -Switches "-t7z" (that is wrong for NSIS, NSIS uses 7z format internally)
     */
    val TAURI_NSIS_MANIFEST = """{
    "version": "1.0.0",
    "description": "Example Tauri desktop application distributed as NSIS installer.",
    "homepage": "https://github.com/example/tauri-app",
    "license": "MIT",
    "architecture": {
        "64bit": {
            "url": "https://github.com/example/tauri-app/releases/download/v1.0.0/TauriApp_1.0.0_x64-setup.exe#/dl.7z",
            "hash": "0000000000000000000000000000000000000000000000000000000000000000"
        }
    },
    "pre_install": "Remove-Item \"\${'$'}dir\\\`\${'$'}*\" -Recurse -Force -ErrorAction SilentlyContinue",
    "bin": "TauriApp.exe",
    "shortcuts": [["TauriApp.exe", "TauriApp"]],
    "checkver": { "github": "https://github.com/example/tauri-app" },
    "autoupdate": {
        "architecture": {
            "64bit": {
                "url": "https://github.com/example/tauri-app/releases/download/v\${'$'}version/TauriApp_\${'$'}version_x64-setup.exe#/dl.7z"
            }
        }
    }
}""".trimIndent()

    /**
     * NSIS GUI-only desktop app (official extras bucket `pot.json` pattern).
     *
     * Pure GUI apps have no CLI use case, so they legitimately omit `bin` and
     * define only `shortcuts`. The installer URLs carry `#/dl.7z` so Scoop
     * extracts the NSIS payload with 7-Zip before installation.
     */
    val POT_DESKTOP = """{
    "version": "3.0.7",
    "description": "A powerful text selection translation and OCR software",
    "homepage": "https://pot-app.com",
    "license": "GPL-3.0-only",
    "architecture": {
        "64bit": {
            "url": "https://github.com/pot-app/pot-desktop/releases/download/3.0.7/pot_3.0.7_x64-setup.exe#/dl.7z",
            "hash": "0a6ac5309bfc41234d58cf6615a779c66d167d3a0da5542a0de2ea118fc6bc56"
        },
        "32bit": {
            "url": "https://github.com/pot-app/pot-desktop/releases/download/3.0.7/pot_3.0.7_x86-setup.exe#/dl.7z",
            "hash": "ff9b69bea285ca60b9f0075a740dc022f9baf873221b14e2c20a960502f09761"
        },
        "arm64": {
            "url": "https://github.com/pot-app/pot-desktop/releases/download/3.0.7/pot_3.0.7_arm64-setup.exe#/dl.7z",
            "hash": "fd478816fa62c137bc0c187a09b82aa37c52b8ccbd441ef715e87c02a45dbc0e"
        }
    },
    "post_install": "Remove-Item \"${'$'}dir\\`${'$'}*\", \"${'$'}dir\\Uninstall*\" -Force -Recurse -ErrorAction Ignore",
    "shortcuts": [
        [
            "pot.exe",
            "Pot"
        ]
    ],
    "checkver": { "github": "https://github.com/pot-app/pot-desktop" },
    "autoupdate": {
        "architecture": {
            "64bit": {
                "url": "https://github.com/pot-app/pot-desktop/releases/download/${'$'}version/pot_${'$'}version_x64-setup.exe#/dl.7z"
            },
            "32bit": {
                "url": "https://github.com/pot-app/pot-desktop/releases/download/${'$'}version/pot_${'$'}version_x86-setup.exe#/dl.7z"
            },
            "arm64": {
                "url": "https://github.com/pot-app/pot-desktop/releases/download/${'$'}version/pot_${'$'}version_arm64-setup.exe#/dl.7z"
            }
        }
    }
}""".trimIndent()

    val ALL = listOf(
        "### Simple GitHub autoupdate (no checksum)\n$RIPGREP",
        "### GitHub autoupdate + checksums.txt (bundle hash)\n$LAZYGIT",
        "### MSI direct extract + persist\n$SEVEN_ZIP",
        "### GUI zip with single primary executable\n$WINMERGE",
        "### GUI zip with pre_install rename + persist\n$SUMATRAPDF",
        "### CLI with env_set + pre_install copy + persist (config from %APPDATA%)\n$BAT_CLI",
        "### env_add_path instead of bin (version manager with shims)\n$PYENV_WIN",
        "### NuGet nupkg with RELEASES hash (Squirrel AutoUpdater)\n$CARNAC_NUPKG",
        "### InnoSetup installer (innosetup:true triggers innounp auto-extraction)\n$BLOBSAVER",
        "### Tauri NSIS installer (#/dl.7z + Remove-Item \$PLUGINSDIR, no double extraction)\n$TAURI_NSIS_MANIFEST",
        "### NSIS GUI-only app (shortcuts only, no bin, #/dl.7z extraction)\n$POT_DESKTOP"
    ).joinToString("\n\n---\n\n")
}
