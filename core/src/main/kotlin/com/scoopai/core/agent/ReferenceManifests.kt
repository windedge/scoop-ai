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
    "checkver": "github",
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
    "checkver": "github",
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

    val ALL = listOf(
        "### Simple GitHub autoupdate (no checksum)\n$RIPGREP",
        "### GitHub autoupdate + checksums.txt (bundle hash)\n$LAZYGIT",
        "### MSI direct extract + persist\n$SEVEN_ZIP"
    ).joinToString("\n\n---\n\n")
}
