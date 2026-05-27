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
      "source": "release_api or official_page or derived"
    }
  ],
  "notes": "Any additional context for the analyze phase",
  "alternatives": "Only when status=no_windows_assets: suggested alternatives",
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
  "pre_install": ["script line 1", "script line 2"],
  "installer_scripts": {
    "64bit": ["Expand-7zipArchive ... app-64.7z ...", "Remove-Item ..."],
    "32bit": ["Expand-7zipArchive ... app-32.7z ...", "Remove-Item ..."]
  },
  "env_set": { "ENV_VAR_NAME": "${'$'}dir" },
  "installer_type": "zip or msi or innosetup or nsis",
  "notes": "Any additional context for the generate phase"
}

Rules:
- entries[].hash MUST be computed via computeSha256FromUrl, NEVER fabricated.
- bin should only contain user-facing executables, NOT helper/proxy/internal tools.
- shortcuts format: [executable, shortcut_name] or [executable, shortcut_name, start_dir].
- pre_install lines are PowerShell statements.
- **NSIS Electron per-arch scripts**: When detectInstallerType returns nsis + nsis_type:electron AND the archive contains per-architecture inner archives (e.g. `` `${'$'}PLUGINSDIR/app-64.7z `` and `` `${'$'}PLUGINSDIR/app-32.7z ``), use `installer_scripts` (map of architecture → script lines) instead of putting Expand-7zipArchive into `pre_install`. Each architecture gets its own Expand-7zipArchive command targeting the matching inner archive. **CRITICAL**: In PowerShell script lines, you MUST escape the dollar sign: use `` `${'$'}PLUGINSDIR `` (backtick-dollar) NOT `${'$'}PLUGINSDIR` — without the backtick, PowerShell treats `${'$'}PLUGINSDIR` as an undefined variable and expands it to empty string, causing "file not found" errors. Common cleanup (Remove-Item `` `${'$'}PLUGINSDIR ``, `Uninstall*`) should also go inside each architecture's script. Non-architecture-specific scripts (persist init, config copy) still go into global `pre_install`.
- If a persist target was found by reading source code but does NOT exist in the archive (runtime-created files like Settings.json, or directories in %APPDATA%), still include it in persist and add pre_install to create the initial file/directory.
- ONLY CALL TOOLS. Do NOT output text responses. When done, call finalize_task_result.
""".trimIndent()
}
