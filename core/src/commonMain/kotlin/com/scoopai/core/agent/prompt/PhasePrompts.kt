package com.scoopai.core.agent.prompt

import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.ReferenceManifests
import com.scoopai.core.agent.ResearchOutput

/**
 * Builds the prompt for the Research phase.
 *
 * @param url The project URL to research.
 * @param outputPath Optional output file path for the manifest.
 */
fun buildResearchPrompt(url: String, outputPath: String?, userPrompt: String? = null): String {
    val task = buildString {
        append("Research the project at: $url")
        if (outputPath != null) append("\nOutput file: $outputPath")
        if (!userPrompt.isNullOrBlank()) append("\n\n## Additional User Requirements\n$userPrompt")
    }
    return """You are now in the RESEARCH phase. Your task is to gather information about the project using research tools (fetchRepoInfo, fetchLatestRelease, fetchAllReleases, fetchWebPage, etc).

$task

## Your Workflow

1. **Parse the input URL**: Extract owner and repo from the URL. Use parseGitHubUrl for GitHub URLs.

2. **Gather project information**:
   - Use fetchRepoInfo to get description, license, homepage.
   - **License precision**: If the GitHub API returns a short SPDX id (for example `GPL-3.0`, `GPL-2.0`), use `fetchJson` to list the repository root directory (`https://api.github.com/repos/{owner}/{repo}/contents`) — this returns all top-level files in ONE call. Find the actual license filename from the listing (e.g. `LICENSE`, `LICENSE.txt`, `COPYING`, `COPYRIGHT`), then use `fetchFileContent` to read exactly that file to determine the correct `-only` or `-or-later` suffix. For multi-licensed projects, list all identifiers comma-separated.
   - Use fetchLatestRelease to find the latest version and assets.
   - **Important**: If the release assets contain checksums files (e.g. `checksums.txt`, `sha256sums.txt`, or files ending with `.sha256`), record the exact filename in the `checksums_filename` field. This will be used later to add an `autoupdate.hash` block.
   - If fetchLatestRelease returns empty, use fetchAllReleases as fallback.
   - For non-GitHub projects, use fetchWebPage to read the project page.

3. **CRITICAL: Handle GitHub API rate limits immediately**:
   - If ANY GitHub API call returns JSON with `"error_type": "rate_limited"` or status 403, **STOP making further GitHub API calls immediately**.
   - Do NOT retry the same or different GitHub API endpoints after a rate limit error.
   - Instead, work with whatever data you already have and proceed to step 7.
   - You may still use fetchWebPage for non-GitHub-API URLs (official websites, etc.).

4. **Prefer the shortest path to Windows download URLs**:
   - **First** check GitHub release assets from `fetchLatestRelease`. If the returned assets already contain `.zip`, `.exe`, or `.msi` files for Windows, STOP immediately — you have everything needed. Only visit the homepage or official download page when GitHub releases have NO usable assets.
   - If GitHub releases have no assets, switch quickly to the official download page or homepage.
   - If the official site exposes version variables, download prefixes, filenames, or direct links in HTML/JS, use those values to derive the final download URLs.
   - A URL is acceptable if it is directly present on the page, or can be deterministically constructed from literal page variables on that same page.
   - After you have enough information, STOP searching and report the results.

5. **Avoid broad unfocused fallback loops**:
   - Do NOT keep crawling multiple GitHub HTML pages once you already know the release API has no assets.
   - Do NOT repeatedly fetch `releases`, `expanded_assets`, guessed tag pages, guessed directory indexes, or random candidate URLs unless the current page explicitly points to them.
   - Prefer at most one official download page, one resolved redirect target, and one closely related page explicitly linked from them.

6. **Identify Windows assets**:
   - Look for .zip, .exe, .msi files in release assets or official download pages.
   - **Prioritize portable ZIP archives over MSI installers**: ZIP is always the preferred format for Scoop. Only fall back to MSI when no suitable ZIP asset exists for Windows.
   - **Asset variant priority** (when multiple variants exist for the same architecture):
     1. **"all" or unified variants** over provider-specific sub-variants (e.g. prefer `terraformer-all-windows-amd64` over `terraformer-google-windows-amd64`).
     2. **Desktop/GUI variants** over CLI-only variants when the project is a desktop application.
     3. **Complete/standard packages** over minimal packages (e.g. prefer `full-portable` over `portable` when both exist)
   - Architecture detection from filename:
     - `x64`, `x86_64`, `amd64`, `win64` → 64bit
     - `x86`, `i686`, `win32`, `i386` → 32bit
     - `arm64`, `aarch64` → arm64
   - Exclude assets containing: `darwin`, `linux`, `macos`
   - Exclude extensions: `.sha256`, `.sig`, `.asc`, `.pem`
   - Exclude source archives containing `source` or `src` with `.tar.gz`
   - If there are ambiguous candidates, use the `__ask_user__` tool to ask which to include.

7. **Discover persist targets — prefer source archive search over file-by-file reading** — Unless the user requirements explicitly say to skip persist discovery:

   **Strategy (follow this order, minimize API calls):**

   a. **List root directory** (ONE fetchJson call): Use `fetchJson` to list the repository root directory (`https://api.github.com/repos/{owner}/{repo}/contents`). The response shows all top-level files, telling you exactly which manifest/project files exist (e.g. `package.json`, `Cargo.toml`, `go.mod`, `README.md`). Pick the correct manifest file based on the listing and read it with ONE `fetchFileContent` call. Do NOT guess filenames — the listing already tells you what exists.
      If this single file reveals framework info (Electron, Tauri) or config patterns, note them in persist_targets/env_targets.

   b. **Search the source code archive** (ONE searchTextInArchive call):
      - Source zip URL pattern: `https://github.com/{owner}/{repo}/archive/refs/tags/{tag}.zip`
      - If the tag starts with `v`, include the `v` prefix in the URL (e.g. `v1.2.3`).
      - Search patterns: `XDG_CONFIG_HOME,electron-store,app.getPath,APPDATA,LOCALAPPDATA,config_dir,getConfigDir`
      - This single call replaces reading 5-10 individual files via fetchFileContent.

   c. **Stop after step (a) and (b)**. Do NOT make additional fetchFileContent calls for source files unless you have zero matches and need to check one specific config file.

   **Framework-specific notes:**
   - **Electron apps**: If package.json shows `electron` dependency, the `Data/` directory in the archive is Electron-managed internal data and MUST NOT be added to persist_targets. User data lives at `%APPDATA%/<app-name>`.
   - **Tauri apps**: If `tauri.conf.json` exists, read `tauri.bundle.identifier` → config at `%APPDATA%/<identifier>`.
   - **Env var overrides**: If source uses an env var to override config path (e.g. `BAT_CONFIG_DIR`, `XDG_CONFIG_HOME`), record the env var name and corresponding directory in `env_targets`.

8. **No Windows assets**: If after all research you find that the project has **no Windows assets at all**, set `"status": "no_windows_assets"` in your output JSON and fill the `alternatives` field.

## CRITICAL RULES

- You can ONLY use these tools: parseGitHubUrl, fetchRepoInfo, fetchLatestRelease, fetchAllReleases, listReleaseAssets, fetchFileContent, fetchWebPage, resolveRedirect, fetchJson, searchTextInArchive, __ask_user__.
- **Rate limit rule**: After receiving a `rate_limited` error or 403, you MUST NOT make any more GitHub API calls. Use searchTextInArchive on the source code zip, or proceed to finalize immediately with available data.
- **Persist discovery rule**: Prefer searchTextInArchive (1 call) over multiple fetchFileContent calls for source code analysis.
- **Stop guessing filenames**: Before reading any file, first list the root directory with `fetchJson("https://api.github.com/repos/{owner}/{repo}/contents")` to see what files actually exist. This replaces multiple wild guesses with one deterministic call.
- **Do NOT re-fetch files you already fetched**: If you already read a file earlier in the session (e.g. `LICENSE` at step 2, `README.md` at step 4), do NOT fetch it again later. Use the content you already have.
- Do NOT call any other tool names.

${PhaseSchemas.RESEARCH_SCHEMA}"""
}

/**
 * Builds the prompt for the Analysis phase.
 *
 * @param research The structured output from the Research phase.
 */
fun buildAnalyzePrompt(research: ResearchOutput, userPrompt: String? = null): String {
    val researchJson = kotlinx.serialization.json.Json.encodeToString(ResearchOutput.serializer(), research)
    val userReq = if (!userPrompt.isNullOrBlank()) "\n\n## Additional User Requirements\n$userPrompt" else ""
    return """You are now in the ANALYSIS phase. Your task is to compute hashes and inspect archive contents using the analysis tools (computeSha256FromUrl, listArchiveContents, findExecutables, detectInstallerType).

CRITICAL: You can ONLY use these tools: computeSha256FromUrl, listArchiveContents, findExecutables, detectInstallerType, __ask_user__. Do NOT call any research tools (fetchRepoInfo, fetchWebPage, etc.) or any other tool names — they are NOT available in this phase.

Based on the following research results, analyze only the most likely Scoop manifest assets first.

```json
$researchJson
```

## Your Task

1. **First choose the preferred installation strategy before doing deep analysis**:
   - Prefer **portable ZIP assets** when they are available and suitable for Scoop.
   - If no suitable ZIP assets exist, then analyze installer assets.
   - For GUI apps with both ZIP and installer variants, choose ONE primary strategy first.
   - Low-priority candidates should be ignored once a good manifest path is found.

2. **Compute hashes**:
   - Use computeSha256FromUrl for the assets that are actually likely to appear in the manifest.
   - NEVER fabricate or guess hashes.
   - Do NOT hash every possible candidate if many are obviously lower priority.

3. **Inspect archives for selected manifest candidates**:
   - For ZIP: Use listArchiveContents to see directory structure, findExecutables to locate .exe files.
   - For MSI (only when no ZIP is available): Use listArchiveContents to see the full extracted file paths. The tool auto-detects archive type and returns paths like `PFiles/AppName/app.exe`. You MUST set `extract_dir` to the directory containing the executable with backslashes ONLY (e.g. `PFiles\\AppName`), because Scoop extracts MSI into subdirectories. Omitting `extract_dir` for MSI will cause "File doesn't exist" errors at install time. Never use forward slashes.
   - Only use detectInstallerType when you are actually choosing an installer/portable-exe strategy, or when ZIP analysis is insufficient.
   - **CRITICAL**: If `detectInstallerType` returns `innosetup` but `listArchiveContents` returns empty (`"files":[]`), it means Scoop's innounp tool cannot unpack this InnoSetup version. Do NOT use `"innosetup": true`. Check if a .zip asset exists instead.

4. **Filter executables aggressively**:
   - Prefer the main end-user executable for the app.
   - Exclude names containing terms like: `helper`, `proxy`, `updater`, `update`, `uninstall`, `setup`, `crash`, `service`, `broker`, `elevat`, `patch`, `pluginproxy`.
   - For GUI apps, `bin` should usually contain only the main launchable app, not internal support tools.

5. **Set persist, env_set, and pre_install from research findings**:
   - The research JSON may contain `persist_targets` and `env_targets`.
   - Use `listArchiveContents` to check which persist targets exist inside the archive.
   - **Rule A**: If a persist target EXISTS in the archive, add it directly to `persist`.
   - **Rule B**: If a persist target does NOT exist in the archive but was found through source code analysis (runtime-created files), still add it to `persist`, AND add `pre_install` code to create the initial file/directory.
   - **Rule C**: If `env_targets` is present (e.g. `BAT_CONFIG_DIR`), set `env_set` to map the env var to `${'$'}dir`, persist the corresponding directory, and add `pre_install` to copy existing config from `%APPDATA%`.
   - **Electron exclusion**: If the research identified this as an Electron app, do NOT add `Data/` or any Electron internal directory to persist.

6. **GUI app packaging**:
   - If a ZIP contains a single versioned primary executable (for example `App-1.2.3-64.exe`), consider using `pre_install` to rename it to a stable filename such as `App.exe`.
   - For GUI apps, add `shortcuts` when appropriate.

$userReq

If the research output contains a `suggested_app_name`, include it unchanged in your analysis output JSON as `suggested_app_name` so the generate phase can use it.

## bin Rules

- Single exe → `"bin": "app.exe"`
- Multiple exes → only include multiple entries when they are all clearly user-facing commands
- **Clean alias is REQUIRED** when the exe name is not a clean command: version numbers (`x86_64-mingw64-app-0.2.3.0.exe`), platform prefixes (`cli-app-windows-amd64.exe`), or any exe name longer than 20 chars — add a short alias
- **Versioned exe + autoupdate is broken**: if the exe name contains a version number, you MUST either use `pre_install` to rename to a stable name, or use alias format

## Installer Type Handling

- ZIP/7z/tar → `url` + `bin` (extract and use)
- MSI → Treat EXACTLY like ZIP. Scoop auto-extracts MSI internally. Just set `url`, `extract_dir` (backslashes ONLY), and `bin`. Do NOT add `"installer"` block.
- InnoSetup → `"innosetup": true` (uses innounp to unpack, NOT silent install)
- NSIS → Append `#/dl.7z` to the URL to force 7z extraction. Do NOT add `"installer"` block.
- **NSIS Electron** (`detectInstallerType` returns `nsis` + `nsis_type: electron`): Two-layer extraction pattern. Use `installer_scripts` (per-architecture) for `Expand-7zipArchive` commands — each arch targets its own inner 7z (e.g. `` `${'$'}PLUGINSDIR/app-64.7z `` for 64bit, `` `${'$'}PLUGINSDIR/app-32.7z `` for 32bit). **CRITICAL**: In PowerShell script lines, you MUST escape the dollar sign: use `` `${'$'}PLUGINSDIR `` (backtick-dollar) NOT `${'$'}PLUGINSDIR` — without the backtick, PowerShell treats `${'$'}PLUGINSDIR` as an undefined variable and expands it to empty string. Common cleanup goes inside each architecture's script too. GUI apps: use `shortcuts` only, no `bin`.
- Portable EXE → `url` with `#/app.exe` fragment to rename

IMPORTANT: You MUST call the analysis tools (computeSha256FromUrl, listArchiveContents, findExecutables) to compute real values. Do not guess or estimate hashes.

${PhaseSchemas.ANALYZE_SCHEMA}"""
}

/**
 * Builds the prompt for the Refine phase (replaces generate).
 *
 * The skeleton already contains version, description, homepage, license,
 * url, hash, bin, extract_dir, persist, shortcuts, env_set, pre_install.
 * This prompt asks the LLM to:
 * 1. Apply installer-type transformations (NSIS→#/dl.7z, InnoSetup→innosetup:true, portable→#/app.exe)
 * 2. Add checkver and autoupdate
 * 3. Self-review via __review_manifest__ tool, fixing issues found
 * 4. Validate and fix any errors
 * 5. Output the final manifest via finalize_task_result
 *
 * @param skeleton Pre-assembled manifest JSON skeleton from prepareManifest.
 * @param analyze The structured output from the Analysis phase (used for installer_type, entries, etc.).
 * @param outputPath Optional output file path for the manifest.
 * @param userPrompt Additional user requirements.
 * @param research The structured output from the Research phase (optional, used for checksums info).
 */
fun buildRefinePrompt(skeleton: String, analyze: AnalyzeOutput, outputPath: String?, userPrompt: String? = null, research: ResearchOutput? = null): String {
    val suggestedAppName = analyze.suggested_app_name?.takeIf { it.isNotBlank() }
    val installerTypeNote = when (analyze.installer_type?.lowercase()) {
        "nsis" -> "\n- NSIS installer detected: append `#/dl.7z` to URL. Do NOT add `\"installer\"` block."
        "nsis_electron" -> "\n- NSIS Electron detected: skeleton contains per-arch `installer.script` with Expand-7zipArchive commands. Review and preserve them. GUI app: use `shortcuts` only, no `bin`."
        "innosetup" -> "\n- InnoSetup detected: set `\"innosetup\": true` (ONLY if `listArchiveContents` returned files; otherwise skip and prefer ZIP)."
        "portable_exe" -> "\n- Portable EXE detected: append `#/app.exe` to URL for rename."
        "msi" -> "\n- MSI detected: set `extract_dir` (backslashes ONLY) alongside `url` and `bin`. Do NOT add `\"installer\"` block."
        else -> ""
    }
    val checksumsNote = research?.checksums_filename?.takeIf { it.isNotBlank() }?.let { filename ->
        "\n\n## Checksums Available\n" +
        "The research phase found a checksums file in the release assets: `$filename`.\n" +
        "You MUST add an `autoupdate.hash` block pointing to this file (see step 3 below).\n"
    } ?: ""
    val outputPart = if (outputPath != null) "\nOutput file: $outputPath" else ""
    val suggestedPart = if (suggestedAppName != null) {
        "\n\n## Suggested App Name\n\nThe analysis suggests app name: `$suggestedAppName`.\n" +
        "When calling `writeManifestToFile`, pass `\"bucket/$suggestedAppName.json\"` as the `path` parameter.\n"
    } else ""
    val userReq = if (!userPrompt.isNullOrBlank()) {
        "\n\n## Additional User Requirements\n$userPrompt\n\n" +
        "When user requirements specify a particular variant or edition (e.g. SYCL-enabled, CUDA, Vulkan, desktop, GUI, CLI), you MUST:\n" +
        "1. Reflect the variant in the `description` field.\n" +
        "2. Ensure the chosen assets match the requested variant."
    } else ""
    return """You are now in the REFINE phase. Your task is to take the pre-assembled manifest skeleton below, apply installer-specific transformations, review the pre-generated checkver/autoupdate, add autoupdate.hash if possible, validate, and finalize.

CRITICAL: You can ONLY use these tools: validate, __ask_user__, finalize_task_result. Do NOT call research or analysis tools.

## Reference Manifests

${ReferenceManifests.ALL}

## Pre-assembled Manifest Skeleton
```json
$skeleton
```
$outputPart$suggestedPart$userReq$checksumsNote

## Installer Type Hints
- `extract_dir`: ONLY when the executable is inside a NAMED subdirectory. If exe is at archive root, OMIT `extract_dir` entirely — do NOT use `"."` or `""`.
$installerTypeNote

## Your Task

1. **Review the skeleton** and make any needed adjustments:
   - Correct `bin` alias format when exe names are versioned or platform-prefixed.
   - Ensure `extract_dir` uses backslashes ONLY (MSI/7z).
   - Apply installer-type URL fragments (NSIS `#/dl.7z`, portable `#/app.exe`).
   - Add `innosetup: true` ONLY when installer_type is `innosetup` and analysis confirmed unpackable files.

2. **PRESERVE the skeleton's autoupdate URLs — DO NOT regenerate them**:
   - The `autoupdate.architecture.<arch>.url` fields were **pre-generated deterministically** from the actual download URLs by replacing the concrete version string with `\${'$'}version`. This correctly preserves `v` prefixes and all other URL patterns.
   - Your task is to **keep them as-is** — just verify they look correct.
   - **CRITICAL**: Do NOT regenerate the autoupdate URL templates from scratch. If you regenerate, you will likely drop the `v` prefix before `\${'$'}version` or make other mistakes.
   - Only fix the pre-generated templates if you can clearly see they are wrong (e.g., URL-encoded chars like `%2B` need special handling).
   - For non-standard GitHub release tags (e.g. `mask/0.11.7` instead of `v0.11.7`), add an appropriate `regex` to `checkver`.

3. **Add autoupdate.hash if checksums are available**:
   - Many GitHub projects publish checksums alongside release assets. The `hash` part of `autoupdate` is NOT pre-generated — you must add it manually.
   - Common patterns (choose the one that matches the project's checksums file):
     * `"hash": { "url": "\${'$'}baseurl/checksums.txt" }` — for `checksums.txt` at the release base URL
     * `"hash": { "url": "\${'$'}baseurl/sha256sums.txt" }` — for `sha256sums.txt` at the release base URL
     * `"hash": { "url": "\${'$'}baseurl/sha256sums.txt", "regex": "\${'$'}sha256\\\\s+\\\\*\${'$'}basename" }` — for hash in `sha256 *filename` format
     * `"hash": { "url": "\${'$'}url.sha256" }` — for per-file `.sha256` files
   - If the research section above says checksums are available, follow it.
   - When in doubt, use the first pattern (`checksums.txt`). An incorrect hash URL will NOT cause install errors — Scoop simply skips hash verification on update if the URL returns 404.

4. **Self-Review**: Call `__review_manifest__` with your complete manifest JSON (as a string).
   The review agent is an **independent reviewer** with NO generation context — it will catch issues
   like unnecessary persist/env_set, inaccurate license, wrong bin, or missing autoupdate.hash.
   - If the review returns `"approved": false`, read the `issues` array, apply each fix to your manifest,
     then proceed to step 5 (validate).
   - If the review returns `"approved": true`, proceed to step 5 (validate) as well.

5. **Validate**: Call `validate` on the complete manifest JSON. Fix any errors found.

6. **Finalize**: After step 5 returns `valid:true`, call `finalize_task_result` with the
   **complete manifest JSON string** starting with `{` and ending with `}`.

## Thinking Model Rules

- Do NOT get stuck in long reasoning loops.
- After any reasoning, you MUST immediately follow it with a tool call or `finalize_task_result`.
- NEVER end your turn with only reasoning text.
- Keep reasoning concise (under 200 tokens).

## Tool Call Rules

- ALWAYS provide ALL required parameters when calling a tool. Never call a tool with empty `{}` arguments.
- Plan the tool call parameters BEFORE making the call, not after."""
}
