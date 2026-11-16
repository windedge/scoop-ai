package com.scoopai.core.agent.prompt

/**
 * JSON schema descriptions embedded into prompts for each phase.
 *
 * These schemas tell the LLM the exact output format expected
 * when calling `finalize_task_result`.
 */
object PhaseSchemas {

    val RESEARCH_SCHEMA: String = """
When you have finished researching, call the `finalize_task_result` tool with your findings as the `result` parameter. The result must be a JSON object conforming to this schema:

{
  "status": "ok" or "no_windows_assets",
  "project": "owner/repo or project name",
  "description": "Brief project description",
  "homepage": "Project homepage URL",
  "license": {
    "value": "SPDX identifier (e.g. MIT, GPL-3.0-or-later, Apache-2.0)",
    "source": "Where you found it (GitHub API, LICENSE file, Cargo.toml, etc.)"
  },
  "version": "Latest version without leading v",
  "windows_assets": [
    {
      "url": "Direct download URL",
      "filename": "Filename on disk",
      "architecture": "64bit or 32bit or arm64",
      "asset_type": "zip or exe or msi",
      "source": "release_api or official_page or derived",
      "hash": "SHA-256 hash if available from GH API (optional, use when provided)",
      "size": "File size in bytes if available from GH API (optional)"
    }
  ],
  "notes": "Any additional context for the analyze phase",
  "alternatives": "Only when status=no_windows_assets: suggested alternatives",
  "user_explanation": "Only when status=no_windows_assets: a short user-facing explanation of why there are no Windows assets, written in the reply language (fill only when status = no_windows_assets)",
  "suggested_app_name": "Kebab-case app name reflecting user-requested variant or edition, e.g. llama-cpp-sycl, app-desktop (omit if no variant requested)",
  "persist_targets": ["List of directories or files found through source code analysis that store user config/data. ONLY from source/manifest reading, NOT from archive listing.
    For CLI tools: look for ~/.config/<app>, %APPDATA%/<app>, XDG_CONFIG_HOME, custom config file paths (config.yaml, config.toml, settings.json).
    For Electron apps: check package.json for 'electron' dependency; look for app.getPath('userData'), electron-store mentions. Note: Data/ directory in the archive is Electron-managed and must NOT be persisted.
    For Tauri apps: read tauri.conf.json 'tauri.bundle.identifier' → %APPDATA%/<identifier>.
    For runtime-created files: check source code for File.WriteAllText, JsonSerializer.Deserialize + File.Create patterns writing config/cookie/state files.
    If source uses an env var to override config path (e.g. BAT_CONFIG_DIR, XDG_CONFIG_HOME), note both the env var name and the corresponding directory name (see notes field)."],
  "env_targets": {
    "ENV_VAR_NAME": "directory_name"
  },

Rules:
- If no Windows assets exist, set status to "no_windows_assets" and fill "alternatives" with suggestions.
- The license.value MUST be the most precise SPDX identifier. Use -only or -or-later suffix when the license text indicates it.
- The version MUST NOT have a leading "v".
- When reading README/docs, look for mentions of config directories, data directories, or config files (e.g. ~/.config/app, %APPDATA%, config.yaml, data/, settings/). Report them in persist_targets.
- Use searchTextInArchive on the source code zip to discover persist patterns in a single call, rather than reading individual files with fetchFileContent.
- If GitHub API returns rate_limited (error_type: rate_limited), stop all GitHub API calls and proceed with available data or searchTextInArchive.
- All fields except status, project, version are optional (omit or use empty string/list).

IMPORTANT: The `result` parameter of `finalize_task_result` must be a raw JSON object, NOT a JSON string.
- WRONG: result="{\"version\":\"1.0\",\"homepage\":\"...\"}"  ← string wrapping
- RIGHT: result={"version":"1.0","homepage":"..."}            ← raw JSON object
Do NOT wrap the JSON in quotes or call jsonStr() on it.

- ONLY CALL TOOLS. Do NOT output text responses. When done, call finalize_task_result.
""".trimIndent()

    val ANALYZE_SCHEMA: String = """
When you have finished analyzing, call the `finalize_task_result` tool with your findings as the `result` parameter. The result must be a JSON object conforming to this schema:

{
  "status": "ok" or "needs_clarification",
  "strategy": "portable_zip or installer or portable_exe",
  "entries": [
    {
      "architecture": "64bit or 32bit or arm64",
      "url": "Download URL",
      "hash": "SHA-256 hash (64 hex chars)",
      "filename": "Filename",
      "extract_dir": "Top-level directory inside this archive (string, not object)"
    }
  ],
  "bin": ["app.exe", ["app2.exe", "alias"]],
  "persist": ["config", "data"],
  "shortcuts": [["app.exe", "App Name"]],
  "pre_install": [
    "Expand-7zipArchive \"${'$'}dir\\*-full.nupkg\" -ExtractDir 'lib\\net45' -Removal",
    "Remove-Item \"${'$'}dir\\RELEASES\", \"${'$'}dir\\Update.exe\", \"${'$'}dir\\background.gif\", \"${'$'}dir\\setupIcon.ico\" -Force -ErrorAction SilentlyContinue"
  ],
  "env_set": { "ENV_VAR_NAME": "${'$'}dir" },
  "env_add_path": ["subdir\\bin", "subdir\\shims"],
  "versioned_bin": true or false (default false),
  "bin_aliases": ["alias1", "alias2"],
  "nsis_unsupported": true or false (default false, only relevant when installer_type is nsis),
  "installer_type": "zip or msi or innosetup or nsis or nsis_unsupported",
  "notes": "Any additional context for the generate phase",
  "suggest": {"vcredist": "extras/vcredist2022"}
}

Rules:
- entries[].hash MUST be computed via computeSha256FromUrl, NEVER fabricated.
- bin should only contain user-facing executables, NOT helper/proxy/internal tools.
- shortcuts format: [executable, shortcut_name] or [executable, shortcut_name, start_dir].
- pre_install lines are PowerShell statements.
- **NSIS Electron per-arch scripts**: When detectInstallerType returns nsis + nsis_type:electron AND the archive contains per-architecture inner archives (e.g. `` `${'$'}PLUGINSDIR/app-64.7z `` and `` `${'$'}PLUGINSDIR/app-32.7z ``), use `installer_scripts` (map of architecture → script lines) instead of putting Expand-7zipArchive into `pre_install`. Each architecture gets its own Expand-7zipArchive command targeting the matching inner archive. **CRITICAL**: In PowerShell script lines, you MUST escape the dollar sign: use `` `${'$'}PLUGINSDIR `` (backtick-dollar) NOT `${'$'}PLUGINSDIR` — without the backtick, PowerShell treats `${'$'}PLUGINSDIR` as an undefined variable and expands it to empty string, causing "file not found" errors. Common cleanup (Remove-Item `` `${'$'}PLUGINSDIR ``, `Uninstall*`) should also go inside each architecture's script. Non-architecture-specific scripts (persist init, config copy) still go into global `pre_install`.
- If a persist target was found by reading source code but does NOT exist in the archive (runtime-created files like Settings.json, or directories in %APPDATA%), still include it in persist and add pre_install to create the initial file/directory.
- **env_add_path**: Use instead of `bin` when the app has multiple batch/cmd scripts that all need to be on PATH, or when it manages a shims directory at runtime (e.g. version managers like pyenv, nvm). Do NOT use both `bin` and `env_add_path` — choose one.
- **extract_dir**: Only strip the top-level version directory (e.g. `project-1.2.3`). If the executable is inside a nested subdirectory, do NOT go deeper — use `env_add_path` and subdirectory-prefixed paths in persist/env_set instead. The extract_dir should have AT MOST one path separator.
- **versioned_bin**: MUST be set to `true` when the exe filename contains a version number (e.g. `xdelta3-3.1.0-x86_64.exe`). In this case:
  * Set `bin` to the versioned exe names as found in the archive (one per architecture, matching `entries` order).
  * Set `bin_aliases` to short command names (e.g. `["xdelta3"]`).
  * The skeleton will generate per-architecture `bin` with `[versioned.exe, alias]` format.
  * Do NOT use `pre_install` to rename versioned executables.

IMPORTANT: The `result` parameter of `finalize_task_result` must be a raw JSON object, NOT a JSON string.
- WRONG: result="{\"version\":\"1.0\",\"homepage\":\"...\"}"  ← string wrapping
- RIGHT: result={"version":"1.0","homepage":"..."}            ← raw JSON object
Do NOT wrap the JSON in quotes or call jsonStr() on it.

- ONLY CALL TOOLS. Do NOT output text responses. When done, call finalize_task_result.
""".trimIndent()
}
