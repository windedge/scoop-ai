package com.scoopai.core.agent.prompt

import com.scoopai.core.AssistContext
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.PrefetchResult
import com.scoopai.core.agent.ReferenceManifests
import com.scoopai.core.agent.ResearchOutput
import com.scoopai.core.agent.tools.ToolCallBudget
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Shared system prompt injected once at the agent level. Koog keeps the agent
 * config prompt (including this system message) in every subgraph's LLM context,
 * so all phases (research/analyze/generate/assist/repair) inherit it.
 */
fun buildGeneratorSystemPrompt(): String =
    "You are a Scoop manifest generator. You generate and assist Scoop bucket manifests for Windows software. " +
        "Style: never use em dashes (" + EM_DASH + ") or en dashes (" + EN_DASH + "). " +
        "Use commas, colons, parentheses or plain hyphens (-) instead."

internal const val EM_DASH: String = "\u2014"
internal const val EN_DASH: String = "\u2013"

/**
 * Builds the prompt for the Research phase.
 *
 * @param prefetch Pre-collected project data. The original URL is accessed via [prefetch.originalUrl].
 *   When [prefetch.isGitHub] is true, a data section is injected into the prompt so the LLM
 *   can skip redundant API calls.
 * @param outputPath Optional output file path for the manifest.
 * @param userPrompt Optional additional user requirements.
 */
fun buildResearchPrompt(prefetch: PrefetchResult, outputPath: String? = null, userPrompt: String? = null): String {
    val url = prefetch.originalUrl
    val task = buildString {
        append("Research the project at: $url")
        if (outputPath != null) append("\nOutput file: $outputPath")
        if (!userPrompt.isNullOrBlank()) append("\n\n## Additional User Requirements\n$userPrompt")
    }

    val basePrompt = """You are now in the RESEARCH phase. Your task is to gather information about the project using research tools (fetchRepoInfo, fetchLatestRelease, fetchAllReleases, fetchWebPage, etc).

Before each tool call, write one short sentence stating what you are about to fetch or inspect, and why.

$task

## Your Workflow

1. **Parse the input URL**: Extract owner and repo from the URL. Use parseGitHubUrl for GitHub URLs.

2. **Gather project information**:
   - Use fetchRepoInfo to get description, license (spdx_id), homepage.
   - **Homepage resolution rules** (follow in order):
     1. The homepage MUST be the project's official website URL or GitHub repository URL (e.g. `https://github.com/{owner}/{repo}`).
     2. Do NOT use social/chat/forum links as homepage: Discord (discord.gg, discord.com), Slack, Twitter/X, Mastodon, Reddit, YouTube, or similar community/communication platforms.
     3. If the homepage from fetchRepoInfo is a social/chat link or cannot be determined, use the GitHub repository URL as the default.
   - **Homepage priority**: When multiple candidate homepages exist, use this order:
     1. Official project website / documentation page (e.g. from README "Website" / "Documentation" link)
     2. Dedicated product page (e.g. developers.google.com/workspace/cli)
     3. GitHub repository URL (fallback only)
     Do NOT use GitHub repo URL as homepage if a dedicated website or docs page exists.
   - **License resolution rules** (follow in order):
     1. If `fetchRepoInfo` returns a `license` field with a value (e.g. "MIT", "Apache-2.0"), use that value directly.
     2. If the value is `"NOASSERTION"`, `null`, or absent:
        a. **⚠️ CRITICAL**: You MUST read the actual LICENSE file before concluding any license. Use `fetchFileContent` to read `LICENSE`, `LICENSE.md`, `LICENSE.txt`, or `COPYING` (try in that order). Also check `package.json` → `license` field, `Cargo.toml` → `license` field.
        b. **Proprietary detection rule**: `"Proprietary"` is almost NEVER correct for a public GitHub repository. Before concluding "Proprietary", you MUST exhaust ALL of: (1) LICENSE/COPYING file, (2) GitHub License API via `fetchJson("https://api.github.com/repos/{owner}/{repo}/license")`, (3) README "License" section. Only if ALL above yield no result may you conclude "Proprietary".
        c. **Never infer license from project website pricing/legal pages** — these are about paid plans, not the open-source license.
     3. If the value is `"Proprietary"`: The project is closed-source. Use it as-is.
     4. If the spdx_id is a generic umbrella identifier like `"GPL-3.0"`, `"GPL-2.0"`, or `"AGPL-3.0"`, you may optionally use `fetchJson` to fetch the full repo tree (`https://api.github.com/repos/{owner}/{repo}/git/trees/{default_branch}?recursive=1`) — this returns every file path in ONE call. Find the actual license filename from the listing (e.g. `LICENSE`, `LICENSE.txt`, `COPYING`, `COPYRIGHT`), then use `fetchFileContent` to read exactly that file to determine the correct `-only` or `-or-later` suffix. For multi-licensed projects, list all identifiers comma-separated.
   - Use fetchLatestRelease to find the latest version and assets.
   - **⚠️ RELEASE CHANNEL RULE**: Some projects publish releases on multiple channels: `stable`, `edge`, `nightly`, `beta`, `rc`, `pre-release`. **Prefer the latest stable release** for your final `windows_assets` field. If `fetchLatestRelease` returns a non-stable tag (contains `edge-`, `nightly-`, `beta`, `rc`, `alpha`), use `fetchAllReleases` to find the latest **stable** release tag (without those prefixes) and use that version's assets instead. If no stable release exists (returns ONLY edge/nightly/beta/rc releases), use the latest edge/nightly release as a fallback and note it.
   - **⚠️ VERSION-ASSET ALIGNMENT RULE (CRITICAL)**: The `version` field MUST equal the tag of the release the chosen Windows asset actually belongs to. Scoop substitutes `version` into `autoupdate.url` via `${'$'}version`, so any mismatch silently breaks autoupdate (the URL stays pinned to one concrete version).
     * **Common trap**: the latest *stable* release exists but ships **NO Windows assets** (Linux/macOS only), while a *preview* / *nightly* / *rolling* release on a different tag carries the Windows binary. In that case you MUST:
       1. Take the Windows asset from the preview/nightly release, AND
       2. Set `version` to **that release's tag** verbatim (e.g. `preview-2026-06-15-517ca8163f7c`), NOT the stable semver (e.g. `0.7.0`).
       3. Write a recommended `checkver.regex` in your `notes` that matches this channel's tag (e.g. `releases/tag/((?:preview|nightly)-[0-9a-zA-Z_-]+)`), because plain `{"github":"..."}` would otherwise match the stable tag.
       4. Add to `notes`: "Stable releases lack Windows builds; using <channel> build."
     * **Self-check before finalizing**: confirm the `version` string appears literally inside every chosen Windows asset's `browser_download_url`. If `version` is absent from a URL, the version is misaligned - re-derive it from that asset's actual release tag.
   - **⚠️ EDGE/NIGHTLY FALLBACK**: If `fetchAllReleases` returns ONLY edge/nightly/beta releases and NO stable releases are found, then use the **latest edge/nightly release** as a fallback. This means the project only publishes edge releases as downloadable assets — do NOT waste token budget browsing the project website, git tags, or documentation looking for stable releases that may not exist as downloadable assets. Just note in the `notes` field: "Only edge releases available; using latest."
   - **⚠️ GITHUB LATEST BADGE RULE (web fallback)**: When the API path is unavailable (rate limited) and you fall back to `fetchWebPage` on `github.com/{owner}/{repo}/releases`: a release with the green "Latest" badge is the official stable release; releases with "Pre-release" badge are NOT stable. Even if a pre-release appears first in the list, look down the page for the release carrying the "Latest" badge and use its tag. Only fall back to a pre-release if EVERY release on the page is marked "Pre-release" (no "Latest" badge exists), and note this in the manifest notes.
   - **Only output Windows assets from the latest stable (or fallback edge/nightly) release** in your final `windows_assets` field. Do NOT include assets from older or unrelated releases, even if they have a portable ZIP variant. Older releases are only useful for `checkver`/`autoupdate` pattern inference.
   - **Important**: If the release assets contain checksums files (e.g. `checksums.txt`, `sha256sums.txt`, or files ending with `.sha256`), record the exact filename in the `checksums_filename` field. This will be used later to add an `autoupdate.hash` block.
   - **Important**: If a `RELEASES` file (Squirrel format) is present among release assets, note it — this file contains SHA-1 hashes for nupkg packages and will be used for autoupdate.hash in the generate phase.
   - If fetchLatestRelease returns empty, use fetchAllReleases as fallback.
   - For non-GitHub projects, use fetchWebPage to read the project page.

3. **CRITICAL: Handle GitHub API rate limits immediately**:
   - If ANY GitHub API call returns JSON with `"error_type": "rate_limited"` or status 403, **STOP making further GitHub API calls immediately**.
   - Do NOT retry the same or different GitHub API endpoints after a rate limit error.
   - Instead, work with whatever data you already have and proceed to step 7.
   - You may still use fetchWebPage for non-GitHub-API URLs (official websites, etc.).

4. **Tool coverage — avoid redundant web scraping**:

   Each piece of manifest info is already provided by a specific API tool. Do NOT call `fetchWebPage` when an API tool already provides the data:

   | Info needed | Use this tool | AVOID (always redundant) |
   |------------|---------------|------------------------|
   | Latest version + download URLs | `fetchLatestRelease` | github.com/.../releases HTML pages |
   | **SHA256 hash of release assets** | `fetchLatestRelease` (`assets[].hash` from GitHub `digest` field) | github.com/.../expanded_assets HTML |
   | All release variants (wamp/lemp/portable) | `fetchLatestRelease.assets` array | project.org/download pages |
   | Description | `fetchRepoInfo.description` | project homepage |
   | License | `fetchRepoInfo.license` (uses spdx_id; defaults to `"Proprietary"`) | project.org/pricing |
   | Persist target directories | `searchTextInArchive` with patterns `www,data,config,logs,tmp,usr,etc,htdocs` | project.org/docs/install |
   | File contents from repo | `fetchFileContent` | github.com/.../blob HTML |
   | **Full repo directory tree** | `fetchJson(https://api.github.com/repos/{owner}/{repo}/git/trees/{default_branch}?recursive=1)` — ONE call returns every file path | multiple `/contents/{subdir}` calls |
   | Multiple releases | `fetchAllReleases` (max 3) | github.com/.../releases HTML |

   - **After `fetchLatestRelease` returns Windows assets, you have everything needed for `version`, `url`, and `hash`.** Do not call `fetchWebPage` to verify hashes — the `assets[].hash` field is the official GitHub-provided SHA256.
   - `fetchWebPage` is for **non-GitHub projects only** (where there is no API), or for finding the `checkver` pattern on the official site when GitHub releases are absent.
   - fetchWebPage has a hard budget of ${ToolCallBudget.FETCH_WEB_PAGE_LIMIT} calls per session. Repeated calls to the same URL are auto-rejected.

### description quality rules (CRITICAL MANDATE — follow Scoop manifest standards):
- **Rule 1: Always prioritize GitHub About description**: If `repoInfo.description` (or `fetchRepoInfo.description`) is present, you MUST use it directly as the primary base (simply strip leading/trailing emoji like `🐳 `). Do NOT discard the official title/definition in favor of concatenating random feature sentences from README.
  - *Example*: `repoInfo.description = "🐳 DeepSeek Harness Desktop"` → `description = "DeepSeek Harness Desktop"` (or `"DeepSeek Harness Desktop client"`).
  - *WRONG*: `"Agent Browser with shared human control and remote control through WeChat, Telegram, and Discord"` (Feature dumping is strictly forbidden).
- **Rule 2: Secondary Fallback only**: ONLY if `repoInfo.description` is totally empty or generic (e.g. "A Windows tool"), extract the first concise tagline from `README.md`.
- **Rule 3: Strictly Prohibited**:
  - ❌ **NO Feature / Channel Dumping**: NEVER concatenate feature lists or integration platforms (`with ...`, `through WeChat, Telegram, and Discord`, `supporting A, B, and C`). Focus purely on the tool's core identity (e.g. "Personal AI agent desktop client").
  - ❌ **NO Marketing Fluff**: Avoid buzzwords like "fast", "modern", "powerful", "ultimate", "revolutionary".
  - ❌ **NO Repo/Mirror Meta-description**: For repack/archive/bucket repos, describe the packaged tool itself, NOT the repository/automation.
  - ❌ **NO Author/Framework Boilerplate**: Do NOT include "Built by team X", "Written in Rust/Electron", or installation instructions.

5. **Prefer the shortest path to Windows download URLs**:
   - **First** check GitHub release assets from `fetchLatestRelease`. If the returned assets already contain `.zip`, `.exe`, or `.msi` files for Windows, STOP immediately — you have everything needed. Only visit the homepage or official download page when GitHub releases have NO usable assets.
   - **License and description can be found via GitHub API (fetchRepoInfo, fetchJson, fetchFileContent) without visiting the project website.**
   - **After you have found Windows release assets, do NOT browse the project website.** The website rarely contains different assets than GitHub releases, and crawling it wastes time.
   - If GitHub releases have no assets, switch quickly to the official download page or homepage.
   - If the official site exposes version variables, download prefixes, filenames, or direct links in HTML/JS, use those values to derive the final download URLs.
   - A URL is acceptable if it is directly present on the page, or can be deterministically constructed from literal page variables on that same page.
   - After you have enough information, STOP searching and report the results.

6. **Avoid broad unfocused fallback loops**:
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
   - If there are ambiguous candidates or multiple asset variants, use the `__ask_user__` questionnaire tool with a `questions` array, e.g. `{"questions": [{"prompt": "Which asset?", "options": [{"label": "app-x64.zip (Recommended)"}, {"label": "app-arm64.zip"}, {"label": "app-setup.exe"}]}]}` so the user can interactively select. Do not invent extra fields (e.g. `isMultiSelect` does not exist).

7. **Discover persist targets — prefer source archive search over file-by-file reading** — Unless the user requirements explicitly say to skip persist discovery:

   **Strategy (follow this order, minimize API calls):**

   a. **Get the full repo tree** (ONE fetchJson call): Use `fetchJson` with `https://api.github.com/repos/{owner}/{repo}/git/trees/{default_branch}?recursive=1` (use `default_branch` from `fetchRepoInfo`; default `master`). This single call returns EVERY file path in the repo — you can scan the listing for manifest/project files (`package.json`, `Cargo.toml`, `go.mod`, `README.md`), config files (`*.ini`, `*.conf`), and source files referencing config paths (`APPDATA`, `XDG_CONFIG_HOME`, etc.). Do NOT chain multiple `/contents/{subdir}` calls — the recursive tree gives you everything in one request. If you need to read a specific file, use ONE `fetchFileContent` call afterward.

   b. **Search the source code archive** (ONE searchTextInArchive call):
      Search the source zip for persistence patterns (e.g. `AppData`, `XDG_DATA_HOME`, `config.json`, `settings.ini`, `sqlite3`, `Database`, `LocalStorage`).

   **Config & env var discovery** (use searchTextInArchive on the source archive):
   Search for these patterns to discover config directories and environment variables:
   1. `_CONFIG_DIR`, `_CONFIG_PATH`, `_CONFIG_HOME`, `_DATA_DIR` — env vars that override config paths
   2. `env::var` / `getenv` / `os.Getenv` / `os.environ` / `System.getenv` — language-specific env var reads
   3. `%APPDATA%` / `%LOCALAPPDATA%` — Windows data storage paths
   4. `config_dir` / `config_path` / `data_dir` — config directory variables
   5. `XDG_CONFIG_HOME` — Linux XDG convention
   6. `_HOME`, `_LIB`, `_CFG`, `_PATH` — toolchain env vars (compilers, SDKs, build tools)
   7. `config_dir` / `config_path` / `data_dir` / `home_dir` — generic config path variables

   For each match found, record in `env_targets`: {"ENV_VAR_NAME": "directory_name"}
   Also check README for mentions of config file locations (`%APPDATA%\<app>`, `~/.config/<app>`).

   **Framework-specific notes:**
   - **Electron apps**: If package.json shows `electron` dependency, the `Data/` directory in the archive is Electron-managed internal data and MUST NOT be added to persist_targets. User data lives at `%APPDATA%/<app-name>`.
   - **Tauri apps**: If `tauri.conf.json` exists, read `tauri.bundle.identifier` → config at `%APPDATA%/<identifier>`.
   - **Env var overrides**: If source uses an env var to override config path (e.g. `BAT_CONFIG_DIR`, `XDG_CONFIG_HOME`), record the env var name and corresponding directory in `env_targets`.

8. **No Windows assets**: If after all research you find that the project has **no Windows assets at all**, set `"status": "no_windows_assets"` in your output JSON and fill the `alternatives` field.

## CRITICAL RULES

- You can ONLY use these tools: parseGitHubUrl, fetchRepoInfo, fetchLatestRelease, fetchAllReleases, listReleaseAssets, fetchFileContent, fetchWebPage, resolveRedirect, fetchJson, searchTextInArchive, __ask_user__.
- **Rate limit rule**: After receiving a `rate_limited` error or 403, you MUST NOT make any more GitHub API calls. Use searchTextInArchive on the source code zip, or proceed to finalize immediately with available data.
- **Persist discovery rule**: Prefer searchTextInArchive (1 call) over multiple fetchFileContent calls for source code analysis.
- **Stop guessing filenames**: Before reading any file, first fetch the full repo tree with `fetchJson("https://api.github.com/repos/{owner}/{repo}/git/trees/{default_branch}?recursive=1")` to see every file path in ONE call. This replaces multiple wild guesses or chained `/contents/{subdir}` calls.
- **Do NOT re-fetch files you already fetched**: If you already read a file earlier in the session (e.g. `LICENSE` at step 2, `README.md` at step 4), do NOT fetch it again later. Use the content you already have.
- Do NOT call any other tool names.

${PhaseSchemas.RESEARCH_SCHEMA}"""

    // Append pre-collected data section when prefetch is available for a GitHub project
    if (prefetch.isGitHub) {
        val prefetchJson = Json.encodeToString<PrefetchResult>(prefetch)
        return basePrompt + """

## PRE-COLLECTED DATA (read-only, already gathered for you)

The following data has been pre-collected for you. You do NOT need to call tools to gather this information.
Read the data below and use it directly:

```json
$prefetchJson
```

## YOUR TASKS (simplified)

1. **Description (CRITICAL MANDATE - HIGH FIDELITY)**:
   - If `recommendedDescription` (or `repoInfo.description`) is present, you MUST use it directly as `description` (e.g. "DeepSeek Harness Desktop" or "DeepSeek Harness Desktop client").
   - **DO NOT REPLACE OR DISCARD** vital product keywords (like "DeepSeek Harness") with generic phrases (e.g. do NOT invent "Minke Agent Desktop Client" or dump README subheadings).
   - ONLY if `repoInfo.description` is totally empty or generic, use the core tagline from README.
2. **License**: If `repoInfo.license` is "NOASSERTION" or null, use `fetchFileContent` to read the LICENSE file.
3. **Asset selection**: Review `windowsAssets`. If multiple candidates exist, pick the most appropriate one.
4. **App name**: Determine `suggested_app_name` based on the project name.
5. **Homepage**: Verify `homepageCleaned` is appropriate. If it points to GitHub but the project has a real website, correct it.
6. **Version**: Use `version` from the pre-collected data (already stripped of 'v' prefix).
7. **Missing data**: If any critical information is missing from the pre-collected data, use available tools to fill gaps.
8. **Output**: Call `finalize_task_result` with a valid ResearchOutput JSON.

## AVAILABLE TOOLS (for gap-filling only)

You have: `fetchFileContent`, `fetchAllReleases`, `fetchWebPage`, `searchTextInArchive`, `__ask_user__`
Do NOT call: `fetchRepoInfo`, `fetchLatestRelease`, `parseGitHubUrl` — data already collected.
"""
    }

    return basePrompt
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

Before each tool call, write one short sentence stating what you are about to fetch or inspect, and why.

⚠️ CRITICAL: Do NOT compute hashes for assets that already have a hash from GitHub API. The `windows_assets` JSON from the research phase already includes a `hash` field when available. If hash is present, use it directly — skip computeSha256FromUrl for that asset.

⚠️ CRITICAL: Do NOT call any research tools (fetchRepoInfo, fetchWebPage, etc.) or any other tool names — they are NOT available in this phase.

Based on the following research results, analyze only the most likely Scoop manifest assets first.

```json
$researchJson
```

## Your Task

1. **First choose the preferred installation strategy before doing deep analysis**:
   - **Always prefer the LATEST version.** Do NOT downgrade to an older version just because it has a ZIP asset.
   - Within the same version, prefer portable ZIP over installer.
   - **GUI-only apps (no CLI use case at all, e.g. system tray utilities)**: you MAY omit `bin` and define only `shortcuts`.
   - If the latest version has no ZIP, use the installer (InnoSetup, NSIS, MSI, etc.).
   - Low-priority candidates should be ignored once a good manifest path is found.

2. **Compute hashes**:
⚠️ **API hash priority**: If the research `windows_assets` entry for an asset already has a `hash` field filled in (from GitHub API's `digest`), **use it directly**. Do NOT call `computeSha256FromUrl` for that URL — the hash from GitHub API is authoritative. Only call `computeSha256FromUrl` for assets whose `hash` is empty in the research JSON.
   - NEVER fabricate or guess hashes.
   - Do NOT hash every possible candidate if many are obviously lower priority.
   - **Large assets (even when size > 100MB / hundreds of MB)**: still call `computeSha256FromUrl` for the chosen asset. Hash computation streams to disk and is memory-safe; do NOT skip hashing just because the file is large.
   - **MSI installers (any size)**: MUST call both `computeSha256FromUrl` AND `listArchiveContents`. MSI `extract_dir` is a hard install requirement; omitting hash or extract_dir will fail Scoop install (e.g. "Can't shim").
   - Only skip deep analysis when a zip/7z asset is clearly unusable (corrupt, password-protected, or wrong platform). Never skip MSI analysis due to size alone.

3. **Inspect archives for selected manifest candidates**:
   - For ZIP: Use listArchiveContents to see directory structure, findExecutables to locate .exe files. The tool may return a `nested_archive` field when the ZIP contains another archive inside it.
   - If `nested_archive` returned: add `pre_install` to extract inner archive.
   - Same-name nested zip (name matches URL filename): set notes to `SAME_NAME_NESTED_ZIP:filename.zip`, add in pre_install:
     ```
     Rename-Item "\${'$'}dir\FILENAME" "\${'$'}dir\_inner.zip"
     Expand-7zipArchive "\${'$'}dir\_inner.zip" "\${'$'}dir"
     Remove-Item "\${'$'}dir\_inner.zip"
     ```
   - For MSI (only when no ZIP is available): ALWAYS use listArchiveContents (even for large MSI files) to see the full extracted file paths. The tool auto-detects archive type and returns paths like `PFiles/AppName/app.exe` or jpackage layout `BOSS/BOSS.exe`. You MUST set `extract_dir` to the directory containing the executable with backslashes ONLY (e.g. `PFiles\\AppName` or `BOSS`), because Scoop extracts MSI into subdirectories. Omitting `extract_dir` for MSI will cause "File doesn't exist" / "Can't shim" errors at install time. Never use forward slashes.
   - Only use detectInstallerType when you are actually choosing an installer/portable-exe strategy, or when ZIP analysis is insufficient.
   - **InnoSetup handling**: If `detectInstallerType` returns `innosetup`, set `"installer_type": "innosetup"` regardless. If `listArchiveContents` returns files, use them to set `bin`. If `listArchiveContents` returns empty (innounp cannot list this version), still report `innosetup` — Scoop may still be able to extract. In this case, set `bin` based on common InnoSetup conventions (main exe usually named after the app), or omit `bin` if uncertain.
   - **NSIS unsupported handling**: If `detectInstallerType` returns `nsis_unsupported` (the NSIS installer cannot be extracted by 7z), set `"installer_type": "nsis"` AND `"nsis_unsupported": true` in your output. This tells the generate phase that `#/dl.7z` will NOT work. In this case, look for alternative ZIP or MSI assets for the same version. If no alternative exists, you may need to use a silent install script strategy.

    - **Squirrel installer handling (.nupkg inside Setup.exe / .exe#/dl.7z)**:
      When an installer exe extracted with `#/dl.7z` contains a `*-full.nupkg` archive and `RELEASES` (typical for Squirrel/Electron/Tauri desktop apps):
      * Do NOT set `extract_dir` to `lib\\net45` (first-level 7z extraction does not have `lib\\net45`, which causes "Could not find net45 (error 16)").
      * Add `pre_install` to extract the inner nupkg and clean up installer artifacts:
        `"Expand-7zipArchive \"${'$'}dir\\*-full.nupkg\" -ExtractDir 'lib\\net45' -Removal"`
        `"Remove-Item \"${'$'}dir\\RELEASES\", \"${'$'}dir\\Update.exe\", \"${'$'}dir\\background.gif\", \"${'$'}dir\\setupIcon.ico\" -Force -ErrorAction SilentlyContinue"`
      * Point `bin` and `shortcuts` directly to the main executable in `${'$'}dir` (e.g. `App.exe`).

    ⚠️ **CRITICAL: Re-inspect after adding `#/dl.7z` suffix**: When you decide to use a `#/dl.7z` (or any `#/` fragment) URL suffix to extract an installer, you MUST call `listArchiveContents` on that SUFFIXED URL afterward to verify what files the archive contains. A bare installer URL (e.g. `.exe`, `.cpl`, `.msi`) often fails to extract, but the same URL with a `#/dl.7z` suffix typically succeeds because it targets the inner 7z payload. Do NOT conclude "cannot extract" after only trying the bare URL — if you add a fragment suffix, re-run the archive inspection on the suffixed URL to discover internal files like config files, persist targets, and the real executable layout.

4. **Filter executables aggressively**:
   - Prefer the main end-user executable for the app.
   - Exclude names containing terms like: `helper`, `proxy`, `updater`, `update`, `uninstall`, `setup`, `crash`, `service`, `broker`, `elevat`, `patch`, `pluginproxy`.
   - **`bin` vs `shortcuts`**:
     - **GUI apps with a main executable**: Include the main exe in BOTH `bin` AND `shortcuts`.
       Users may want to launch the app from CLI (e.g. `librecad file.dxf`), so bin is valuable even for GUI apps.
       Only omit `bin` if the app has NO usable CLI interface at all (e.g. a system tray utility).
       * **Every GUI app MUST have `shortcuts`** — this creates Start Menu entries. Omitting `shortcuts` for a GUI app makes it invisible to the user after install.
     - **Dual-mode apps** (CLI + GUI both as primary interfaces, e.g. WezTerm, electerm, VS Code, VNote): use BOTH `bin` (with all CLI entry points including the GUI launcher exe) and `shortcuts` (with the GUI launcher).
       * A terminal emulator, code editor, or note-taking app typically works in both GUI and CLI modes — include both.
     - **Multi-exe apps** (one GUI launcher + multiple unrelated CLI tools): `bin` contains the CLI tools, `shortcuts` contains the GUI launcher — different exes in each.
     - **CLI-only tools**: Use `bin` only, no `shortcuts`.
   - **Heuristic** (follow this decision tree):
     1. Check the research `description` for GUI indicators (see above). Does it describe a desktop application with a user interface?
     2. Check the homepage/screenshots in the research notes — does the project show a desktop window?
     3. Check if the archive contains ONLY executables that are clearly GUI (like `app.exe` with no CLI subcommands).
     4. If ANY of the above indicates a GUI app, add `shortcuts`. Unless the app is truly CLI-only (no GUI at all), add `bin` as well — users benefit from CLI access.
     5. When uncertain: add `shortcuts` + `bin` (extra bin entries are harmless for dual-mode apps).

   - **`shortcuts` name**: Use the full application name as shown in the project's official title or branding (e.g. "Zed Attack Proxy" not "ZAP", "Visual Studio Code" not "VSCode").

5. **Set persist, env_set, env_add_path, and pre_install from research findings**:
   - The research JSON may contain `persist_targets` and `env_targets`.
   - Use `listArchiveContents` to check which persist targets exist inside the archive.
   - **Rule A**: If a persist target EXISTS in the archive, add it directly to `persist`.
   - **Rule B**: If a persist target does NOT exist in the archive but was found through source code analysis (runtime-created files), still add it to `persist`, AND add `pre_install` code to create the initial file/directory.
   - **env_set rule (IMPORTANT — follow ALL sub-steps)**: If `env_targets` is present in the research output (e.g. `{"KOMGA_CONFIG_DIR": "config"}`), you MUST:
     1. **env_set mapping**: Set `env_set` to map each env var to `\${'$'}dir\` followed by the target directory. For `{"KOMGA_CONFIG_DIR": "config"}`, produce `{"KOMGA_CONFIG_DIR": "\${'$'}dir\\config"}`. Do NOT map to just `\${'$'}dir` — always append the subdirectory.
     2. **persist the directory**: Add the corresponding directory (e.g. `"config"`) to `persist` if not already present.
     3. **pre_install to create**: Add `pre_install` code to create the directory if it doesn't exist in the archive (e.g. `"if (!(Test-Path \"\${'$'}dir\\config\")) { New-Item -ItemType Directory -Path \"\${'$'}dir\\config\" | Out-Null }"`).
   - **Electron exclusion**: If the research identified this as an Electron app, do NOT add `Data/` or any Electron internal directory to persist.

   - **CRITICAL: persist paths must be `\${'$'}dir`-relative**:
     - Scoop persist only supports relative paths under `\${'$'}dir` (e.g. `config`, `data`, `.cache`).
     - **NEVER** use `~` (tilde) paths like `~/.cache/app` - Scoop does NOT expand tilde.
     - **NEVER** use absolute paths like `/home/user/app`, `C:\Users\...`, or `\\server\share`.
     - **NEVER** use environment variable references like `%APPDATA%\app` or `%USERPROFILE%\.cache`.
     - **NEVER** use paths starting with `/` or `\\`.
     - If the app stores data in `%APPDATA%`, `%USERPROFILE%`, or `~/.xxx`, do NOT persist it.
       Scoop cannot manage data outside `\${'$'}dir`/`\${'$'}persist_dir`. Instead, add a `notes` entry
       documenting where the app stores its data, and only use `env_set` to redirect to
       `\${'$'}persist_dir` if the app supports explicit env-var-based configuration.

   - **GUI app persist hint**: If the app is GUI and `persist` is empty after source analysis, use `listArchiveContents` to check the archive for:
     - Configuration files (*.ini, *.cfg, *.conf, *.json in root or known config dirs)
     - Settings directories (Settings, Config, Data, etc.)
     - README or documentation mentioning config file locations
     If found, add them to persist_targets.
   - If the source code shows patterns of config file creation (QSettings, app.config, appsettings, SaveSettings, ConfigurationManager), the app likely creates a config file at first run. Add the config filename to `persist_targets` and generate `pre_install` to create it if missing.

6. **extract_dir strategy — ALWAYS strip the top-level version directory**:
   - **⚠️ CRITICAL: Scoop does NOT automatically strip single-root directories.** You MUST set `extract_dir` to the top-level directory name inside the archive.
   - Without `extract_dir`, files stay inside the versioned directory and Scoop will report "Can't shim ... File doesn't exist."
   - Most GitHub release archives have a single top-level directory like `project-1.2.3/` containing the actual files.
   - Set `extract_dir` to ONLY this top-level directory (e.g. `pyenv-win-3.1.1`), never deeper.
   - **Incorrect** (causes install failure): `extract_dir` omitted because "Scoop will strip it automatically" — **Scoop does NOT auto-strip**.
   - **Correct**: `"extract_dir": "project-1.2.3"` for each architecture entry.
   - **extract_dir verification**: Before setting extract_dir, cross-check against the listArchiveContents output:
     - The extract_dir MUST match an actual top-level directory in the archive
     - If the archive contains nested directories like `target/x86_64-pc-windows-msvc/release/`, use the FULL path as extract_dir, not just the first level
     - NEVER use a partial or abbreviated extract_dir — it must be the exact directory name from the archive listing
   - If the executable is inside a nested subdirectory (e.g. `project-1.2.3/tools/bin/app.exe`), do NOT set `extract_dir` to the nested path. Instead:
     * Use `env_add_path` to add the nested `bin` directory to PATH.
     * Use subdirectory-prefixed paths in `persist` and `env_set` (e.g. `tools\\version`, `${'$'}dir\\tools`).
   - **Why**: Going deeper than the top-level directory discards sibling files (config, version files, resources) that the application may need at runtime. Scripts often compute paths relative to their own location by going up parent directories — if `extract_dir` strips too many levels, these relative paths break.
   - **Rule of thumb**: `extract_dir` should have AT MOST one path separator. If it needs two or more, use `env_add_path` + subdirectory prefixes instead.

7. **When to use env_add_path instead of bin**:
   - Use `env_add_path` (NOT `bin`) when:
     * The app has multiple batch/cmd scripts in a `bin/` directory that all need to be on PATH (e.g. `pyenv.bat`, `pyenv-global.bat`, `pyenv-install.bat`).
     * The app manages a `shims/` directory at runtime that must be on PATH (e.g. version managers like pyenv, nvm).
     * The app is primarily driven through wrapper scripts, not a single exe.
   - Use `bin` when there is a clear single (or few) user-facing executable(s).
   - Do NOT use both `bin` and `env_add_path` for the same tool — choose one.
   - If a ZIP contains a single versioned primary executable (for example `App-1.2.3-64.exe`), consider using `pre_install` to rename it to a stable filename such as `App.exe`.
   - For GUI apps, add `shortcuts` when appropriate.

$userReq

If the research output contains a `suggested_app_name`, include it unchanged in your analysis output JSON as `suggested_app_name` so the generate phase can use it.

## bin Rules

- Single exe → `"bin": "app.exe"`
- Multiple exes → only include multiple entries when they are all clearly user-facing commands
- **Clean alias is REQUIRED** when the exe name is not a clean command: version numbers (`x86_64-mingw64-app-0.2.3.0.exe`), platform prefixes (`cli-app-windows-amd64.exe`), or any exe name longer than 20 chars — add a short alias
- **Versioned exe handling** (exe name contains a version number):
  * **MANDATORY**: Set `versioned_bin: true` and provide `bin_aliases` with short command names.
  * Set `bin` to the versioned exe names as found in the archive (one per architecture).
  * **Versioned exe handling**: Do NOT hardcode version numbers in pre_install renames. If you use `Rename-Item`, you MUST use wildcard patterns (e.g. `Get-ChildItem '*app-*.exe' | Rename-Item -NewName 'app.exe'`). Prefer the `versioned_bin` approach when possible.
  * Example:
    ```json
    "bin": ["xdelta3-3.1.0-x86_64.exe"],
    "versioned_bin": true,
    "bin_aliases": ["xdelta3"]
    ```

## Installer Type Handling

- ZIP/7z/tar → `url` + `bin` (extract and use)
- MSI → Treat EXACTLY like ZIP. Scoop auto-extracts MSI internally. Just set `url`, `extract_dir` (backslashes ONLY), and `bin`. Do NOT add `"installer"` block.
- **MSI notes**: If the tool requires environment variable setup or has special CLI usage, include user-facing instructions in `notes` (e.g. automation drivers needing env vars like `APPIUM_WAD_PATH`).
- InnoSetup → `"innosetup": true` (uses innounp to unpack, NOT silent install)
- NSIS → Append `#/dl.7z` to the URL to force 7z extraction. Do NOT add `"installer"` block.
- NSIS unsupported (when `detectInstallerType` returns `nsis_unsupported` or `nsis_unsupported` is true in analyze output): The `#/dl.7z` trick does NOT work for this installer. Set `"installer_type": "nsis"` and `"nsis_unsupported": true`. Look for alternative ZIP/MSI assets for this version. If no alternative exists, use a silent install script with `"installer": { "script": ["..."] }` — try `/S /D=${'$'}dir` for standard NSIS silent flags.
- **NSIS with `${'$'}PLUGINSDIR`** (`detectInstallerType` returns `nsis` + `nsis_type: electron`): The installer has a `${'$'}PLUGINSDIR` directory. Check the `inner_files` list from `detectInstallerType` to determine the subtype:

  a. **Electron NSIS** — `inner_files` contains `${'$'}PLUGINSDIR/app-64.7z` or `${'$'}PLUGINSDIR/app-32.7z`:
     Two-layer extraction pattern. Use `installer_scripts` (per-architecture) for `Expand-7zipArchive` commands — each arch targets its own inner 7z (e.g. `` `${'$'}PLUGINSDIR/app-64.7z `` for 64bit, `` `${'$'}PLUGINSDIR/app-32.7z `` for 32bit). **CRITICAL**: In PowerShell script lines, you MUST escape the dollar sign: use `` `${'$'}PLUGINSDIR `` (backtick-dollar) NOT `${'$'}PLUGINSDIR` — without the backtick, PowerShell treats `${'$'}PLUGINSDIR` as an undefined variable and expands it to empty string. Common cleanup goes inside each architecture's script too. GUI apps: use `shortcuts` only, no `bin`.

  b. **Tauri NSIS** — `inner_files` contains the actual executable(s) directly (e.g. `App.exe`, `*.dll`) but NO `${'$'}PLUGINSDIR/app-64.7z`:
     This is a Tauri (or standard) NSIS installer. The 7z payload from `#/dl.7z` extraction directly contains the application files; **no second extraction is needed**. Set `installer_type: "nsis"` (not "nsis_electron"). Add `pre_install`: `Remove-Item "\${'$'}dir\`${'$'}*" -Recurse -Force` to clean up `${'$'}PLUGINSDIR` and other NSIS remnants. Do NOT use `Expand-7zipArchive` on `${'$'}PLUGINSDIR/app-64.7z` (Tauri does NOT have this file) and do NOT use `-Switches "-t7z"`.
- Portable EXE → `url` with `#/shortname.exe` fragment to rename. **MANDATORY when the exe filename contains version numbers, channel tags (`stable-`, `edge-`, `rc-`), platform suffixes (`-windows-amd64`), or any non-essential decoration.** The fragment after `#/` becomes the local filename.
  * Example: `linkerd2-cli-stable-2.14.10-windows.exe` → URL `.../linkerd2-cli-stable-2.14.10-windows.exe#/linkerd.exe` + `"bin": "linkerd.exe"`
  * The short name should be the canonical CLI command (e.g. `linkerd`, not `linkerd2` or `linkerd2-cli`)
  * Do NOT use this for NSIS installers — those use `#/dl.7z` instead

### NSIS/InnoSetup cleanup patterns
When the installer type is NSIS or InnoSetup (detected via `#/dl.7z` suffix or `installer_type`):
- Use `listArchiveContents` to inspect inner files for cleanup targets: `Uninst*`, `vc_redist*`, `${'$'}PLUGINSDIR`, `${'$'}*`
- If found, generate a `pre_install` script to remove these unnecessary files
- Check for `vcruntime*.dll` or `msvcp*.dll` in the archive — if present, add `suggest: {"vcredist": "extras/vcredist2022"}` to the output

### NuGet (nupkg) / Squirrel AutoUpdater
When a release asset is a `.nupkg` file (NuGet/Squirrel AutoUpdater package):
- Use the nupkg directly as the download URL. Scoop can extract nupkg (it is a zip).
- Set `extract_dir` to the .NET framework subdirectory inside the nupkg. Call `listArchiveContents` on the nupkg URL to confirm the path (commonly `lib\net45`, `lib\net462`, `lib\net6.0`, `lib\net8.0` — do NOT assume, verify).
- Do NOT use `pre_install` with `Move-Item "\${'$'}dir\lib\netXX\*"` to relocate files — use `extract_dir` instead.
- The hash for nupkg packages usually comes from a sibling `RELEASES` file in the same release (Squirrel format), which uses SHA-1 (not SHA-256). Prefer `RELEASES` over `sha256sums.txt` when both exist.

IMPORTANT: You MUST call the analysis tools (computeSha256FromUrl, listArchiveContents, findExecutables) to compute real values. Do not guess or estimate hashes.

**Before finalizing**, consider these commonly missed fields:
- env_set: Does the app read config from a configurable directory? Check research findings for env vars like *_CONFIG_DIR, *_CONFIG_PATH, config_dir. If evidence exists, add to env_set.
- persist: Is there user data that should survive updates?
- `suggest`: If the app depends on external runtime at launch time (e.g. Java/JRE for Java apps, FFmpeg for media tools, VC++ runtime for C++ apps, Node.js for JS tools), add `{"Name": "bucket/name"}`. Only include when the dependency is clearly needed for basic functionality.
If research did not report env_targets but you see evidence of config directories, add them yourself.

${PhaseSchemas.ANALYZE_SCHEMA}"""
}

/**
 * Drops analyze.notes lines that mention checksums files.
 *
 * The same hallucination channel as [buildChecksumsNote] guards: the notes free
 * text may invent a companion/checksum filename and trick generate into a
 * 404ing autoupdate.hash. The authoritative checksums channel is the research
 * phase's checksums_filename (cross-checked against release assets upstream),
 * so any checksums-bearing notes line is untrustworthy and dropped whole-line.
 */
internal fun filterChecksumsNotes(notes: String): String =
    notes.lines().filterNot { line -> line.contains("checksums", ignoreCase = true) }.joinToString("\n").trim()

/**
 * Deterministic cross-check of the research phase's checksums_filename against
 * the actual release asset names collected by prefetch.
 *
 * - filename matches an asset (ignoreCase): strong MUST instruction
 * - no asset list available (empty names, e.g. non-GitHub or rate limited):
 *   downgraded to advisory wording
 * - filename absent from a non-empty asset list: empty string (note dropped)
 */
internal fun buildChecksumsNote(checksumsFilename: String, releaseAssetNames: List<String>): String {
    val filename = checksumsFilename.trim()
    if (filename.isEmpty()) return ""
    if (releaseAssetNames.isEmpty()) {
        return "\n\n## Checksums Available\n" +
            "The research phase reports a checksums file: `$filename`.\n" +
            "The release asset list was not available for verification. If the release really publishes this file, add an `autoupdate.hash` block pointing to it; otherwise omit the hash block.\n"
    }
    val matches = releaseAssetNames.any { it.equals(filename, ignoreCase = true) }
    if (!matches) return ""
    return "\n\n## Checksums Available\n" +
        "The research phase found a checksums file in the release assets: `$filename`.\n" +
        "You MUST add an `autoupdate.hash` block pointing to this file (see step 3 below).\n"
}

/**
 * Builds the prompt for the Generate phase.
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
 * @param checksumsAssetNames Effective release asset names; empty downgrades the checksums note.
 */
fun buildGeneratePrompt(skeleton: String, analyze: AnalyzeOutput, outputPath: String?, userPrompt: String? = null, research: ResearchOutput? = null, checksumsAssetNames: List<String> = emptyList()): String {
    val suggestedAppName = analyze.suggested_app_name?.takeIf { it.isNotBlank() }
    val nsisUnsupported = analyze.nsis_unsupported
    val installerTypeNote = when (analyze.installer_type?.lowercase()) {
        "nsis" -> {
            if (nsisUnsupported) {
                "\n- NSIS installer detected but marked as **unsupported by 7z extraction**. Do NOT append `#/dl.7z`. Instead, add a silent install script: use `\"installer\": { \"script\": \"...\" }` with standard NSIS silent flags (`/S /D=\$dir`)."
            } else {
                "\n- NSIS installer detected: append `#/dl.7z` to URL. Do NOT add `\"installer\"` block." +
                "\n- **Tauri NSIS** (standard NSIS with no `app-64.7z` inner archive): the 7z payload directly contains the application executable(s). Add `pre_install`: Remove-Item `\"\$dir\\`\$*\"` -Recurse -Force to clean up `\$PLUGINSDIR` and other NSIS remnants. Do NOT call Expand-7zipArchive on `\$PLUGINSDIR\\app-64.7z` (that is Electron-specific). Do NOT use `-Switches \"-t7z\"`."
            }
        }
        "nsis_electron" -> "\n- NSIS Electron detected: skeleton contains per-arch `installer.script` with Expand-7zipArchive commands. Review and preserve them. GUI app: use `shortcuts` only, no `bin`."
        "innosetup" -> "\n- InnoSetup detected: set `\"innosetup\": true`. Scoop uses innounp to unpack, NOT silent install."
        "portable_exe" -> "\n- Portable EXE detected: append `#/app.exe` to URL for rename."
        "msi" -> "\n- MSI detected: set `extract_dir` (backslashes ONLY) alongside `url` and `bin`. Do NOT add `\"installer\"` block."
        else -> ""
    }
    val checksumsNote = buildChecksumsNote(research?.checksums_filename ?: "", checksumsAssetNames)
    val outputPart = if (outputPath != null) "\nOutput file: $outputPath" else ""
    val suggestedPart = if (suggestedAppName != null) {
        "\n\n## Suggested App Name\n\nThe analysis suggests app name: `$suggestedAppName`.\n" +
        "When calling `writeManifestToFile`, pass `\"bucket/$suggestedAppName.json\"` as the `path` parameter.\n" +
        "\n" +
        "### Name Conflict Rule\n" +
        "If the verify phase reports an error like 'app name X conflicts with an existing " +
        "manifest in a local Scoop bucket', you MUST rename the app to avoid the conflict.\n" +
        "Preferred rename strategy (in priority order):\n" +
        "1. **Repo name prefix**: Use the full repository name (e.g. `micro` from `go-micro` → `go-micro`).\n" +
        "2. **Owner prefix**: If the repo name is too generic, prepend the GitHub owner (e.g. `micro` → `micro-go-micro`).\n" +
        "3. **Descriptive suffix**: Add a variant/edition suffix (e.g. `micro` → `micro-editor`).\n" +
        "Avoid common single-word names. Update both `suggested_app_name` and the `path` parameter.\n"
    } else ""
    val userReq = if (!userPrompt.isNullOrBlank()) {
        "\n\n## Additional User Requirements\n$userPrompt\n\n" +
        "When user requirements specify a particular variant or edition (e.g. SYCL-enabled, CUDA, Vulkan, desktop, GUI, CLI), you MUST:\n" +
        "1. Reflect the variant in the `description` field.\n" +
        "2. Ensure the chosen assets match the requested variant."
    } else ""
    val notesSection = filterChecksumsNotes(analyze.notes).takeIf { it.isNotBlank() }?.let {
        "\n\n## Analysis Notes\n$it\n"
    } ?: ""
    return """You are now in the GENERATE phase. Your task is to take the pre-assembled manifest skeleton below, apply installer-specific transformations, review the pre-generated checkver/autoupdate, add autoupdate.hash if possible, and finalize. Validation is automatic in a later phase — do not call validate here.

CRITICAL: You can ONLY use these tools: __ask_user__, finalize_task_result. Do NOT call validate, research tools, or analysis tools — they are NOT available in this phase. Validation is handled automatically in a later phase.

## Reference Manifests

${ReferenceManifests.ALL}

## Pre-assembled Manifest Skeleton
```json
$skeleton
```
$outputPart$suggestedPart$userReq$checksumsNote

## Installer Type Hints
$installerTypeNote$notesSection

## SKELETON PRESERVATION RULES

The skeleton above was assembled deterministically. Most fields are already correct.
You are only allowed to ADD missing fields, not MODIFY or RESTRUCTURE existing ones.

### ⚠️ CRITICAL: checkver format

**Recommended: Use the full URL form.**
- ✅ **Recommended**: `"checkver": { "github": "https://github.com/owner/repo" }` — explicitly specifies the repo, works regardless of homepage field
- ⚠️ `"checkver": "github"` — shorthand form, Scoop-supported but **not recommended** because it infers the GitHub URL from the `homepage` field. If homepage is a non-GitHub URL (e.g. project website), Scoop's checkver will fail.
- Always prefer the explicit `{"github":"https://..."}` form to avoid homepage dependency issues.
- **⚠️ Non-semver `version` (channel tags) REQUIRES a `regex`**: When `version` is not a plain semver (e.g. it is a `preview-<date>-<hash>`, `nightly-<date>`, or other rolling tag), plain `{"github":"..."}` checkver would match the wrong (stable) release, and `autoupdate.url` (which uses `${'$'}version`) would point at a release that has no Windows asset. In that case you MUST add a `"regex"` that matches this channel's tag. If the analysis notes recommend a regex (see the VERSION-ASSET ALIGNMENT RULE from research), use it verbatim. Example:
  ```json
  "checkver": { "github": "https://github.com/owner/repo", "regex": "releases/tag/((?:preview|nightly)-[0-9a-zA-Z_-]+)" }
  ```

### ⚠️ CRITICAL: autoupdate structure and extract_dir

The skeleton's `autoupdate` block has the correct structure:
- Single-arch: flat `{"url": "...", "extract_dir": "..."}` without `architecture` wrapper
- Multi-arch: `{"architecture": {"64bit": {...}, "32bit": {...}}}` with wrapper

The `extract_dir` in `autoupdate` is present ONLY when it is version-dependent (contains `${'$'}version`).
A static `extract_dir` (no `${'$'}version`) is already at the manifest top level and does NOT belong in autoupdate.
Do NOT add `extract_dir` to autoupdate if the skeleton does not have it there.

### ⚠️ CRITICAL: bin and shortcuts consistency

- For dual-mode apps (editors, terminals, IDEs, note-taking apps that have both GUI and CLI modes): include the exe in BOTH `bin` and `shortcuts`.
- For GUI apps with a main executable: include the main exe in BOTH `bin` AND `shortcuts`. Users may want to launch from CLI (e.g. `librecad file.dxf`). Only omit `bin` if the app has NO usable CLI interface (e.g. a system tray utility).
- **`bin` placement is mutually exclusive**: if bins are placed inside architecture blocks (architecture-specific `bin`), do NOT also define a top-level `bin`. Choose exactly one location for the `bin` field per manifest: either top-level `bin` OR architecture-specific `bin`, never both. A manifest with both triggers Scoop's install-time error "Both top-level 'bin' and architecture-specific 'bin' are defined" and is rejected.
- **`shortcuts` name**: Use the full application name as shown in the project's official title or branding (e.g. "Zed Attack Proxy" not "ZAP", "Visual Studio Code" not "VSCode").

### Fields to preserve exactly (copy verbatim):
- `checkver` — keep the skeleton's format (either shorthand `"github"` or full `{"github":"https://..."}`)
- `autoupdate` — URL templates, structure (flat vs architecture-wrapped), and `extract_dir` placement are correct
- `extract_dir` — if at top level, keep at top level. Do NOT move into architecture blocks.
- `version`, `description`, `homepage`, `license`, `url`, `hash` — copy verbatim. For description, follow the quality rules from research: app-centric, concise, no marketing fluff, technically precise.
- `bin`, `persist`, `shortcuts`, `pre_install`, `env_set` — copy verbatim.

### Fields you MAY add (only if missing from skeleton):
- `innosetup: true` — only when installer_type is `innosetup`
- `autoupdate.hash` — if checksums file is available
- URL fragment transformations (NSIS `#/dl.7z`, portable `#/app.exe`). Note: MSIX/appx URLs already have `#/dl.7z` in the skeleton — do NOT add it again.
- `"installer": { "script": ["..."] }` — only when `nsis_unsupported` is true, to add a silent install script for NSIS installers that 7z cannot extract
- `notes` — user-facing setup instructions (env vars, config paths, post-install steps). If analysis notes contain setup instructions, include them here as an array of strings.
- `suggest` — runtime dependencies (e.g. Java/JRE for Java apps, FFmpeg for media tools, VC++ runtime for C++ apps, Node.js for JS tools). Only include when the dependency is clearly needed for basic functionality.

### Fields you MUST NOT change:
- Do NOT change the `checkver` format that the skeleton used - EXCEPT when `version` is a non-semver channel tag (see "Non-semver version REQUIRES a regex" above); in that single case you MUST add a `regex` to `checkver`.
- Do NOT move `extract_dir` between top-level and architecture blocks.
- Do NOT add/remove the `architecture` wrapper inside `autoupdate`.
- Do NOT add `extract_dir` inside `autoupdate` if the skeleton does not have it there.

### Same-name nested zip exception
- When pre_install contains `Expand-7zipArchive "\${'$'}dir\FILENAME"` and url has matching ZIP filename: append `#/outer.zip` to URL (hash stays valid).
- Keep pre_install's Rename-Item pattern. Use original filename (before `#/`), not `\${'$'}fname`:
  ```
  Rename-Item "\${'$'}dir\FILENAME" "\${'$'}dir\_inner.zip"
  Expand-7zipArchive "\${'$'}dir\_inner.zip" "\${'$'}dir"
  Remove-Item "\${'$'}dir\_inner.zip"
  ```

## Your Task

1. **Copy the skeleton verbatim** as your starting point.
2. **Apply installer-type transformations** (ONLY modify URL fields):
   - NSIS: append `#/dl.7z` to URL (skip this if `nsis_unsupported` is true in the analyze output — use a silent install script instead)
   - InnoSetup: add `"innosetup": true` as a top-level field
   - Portable EXE: append `#/shortname.exe` to URL. The short name MUST be the canonical command name (e.g. `linkerd` not `linkerd2-cli-stable-2.14.10-windows`). Also set `bin` to the short name.
3. **Add autoupdate.hash if checksums are available**:
   - If the research section above says checksums are available, add the appropriate hash block.

   Common autoupdate hash patterns (pick the most appropriate):
   - Separate checksums file: `{"url": "${'$'}baseurl/checksums.txt", "regex": "${'$'}basename.*?([a-f0-9]{32,})"}`
   - SHA256SUMS/MD5SUMS in release assets: `{"url": "${'$'}baseurl/SHA256SUMS", "find": "${'$'}basename"}`
   - GitHub release HTML page with hash: `{"url": "${'$'}baseurl", "regex": "${'$'}basename.*?([a-f0-9]{32,})"}`
   - RELEASES file for nupkg/Squirrel packages: `{"url": "${'$'}baseurl/RELEASES", "find": "${'$'}basename"}` (Squirrel RELEASES format: `<sha1-hash> <filename>` per line)
   - No checksums available: omit hash block entirely

   ⚠️ GitHub hash reality check (common mistake):
   - GitHub releases RARELY ship a `<file>.sha256` companion next to each asset. Do NOT construct a `${'$'}baseurl/app-${'$'}version.zip.sha256` style URL unless the research section confirms that exact checksums file is a release asset; a guessed companion URL makes every autoupdate fail with a 404.
   - For GitHub releases, rely on the GitHub API `digest` (the research `windows_assets[].hash` / `fetchLatestRelease` `assets[].hash` field) for the current version's `hash`, and omit `autoupdate.hash` if no real checksums file exists in the release assets. A wrong hash is worse than no hash.

4. **Finalize**: Call `finalize_task_result` with the required parameter result plus optional explanation. `result` must be a **STRING containing the manifest JSON text** (NOT a JSON object).

   The tool's `result` parameter has type `string`. Its content is the manifest serialized as JSON text. The inner quotes must be escaped as `\"`.

   `explanation` is an independent short string parameter: at most 3 short sentences, about 60 words total, written in the same language as the user's input. Spend 1-2 sentences on the packaging strategy and at most 1 sentence on any non-obvious choice. Do NOT recap extract_dir / bin / shortcuts / persist field-by-field; the manifest is displayed in the UI, so the reader can see those fields directly. It must never be placed inside `result`.

   `activities` is an optional list of strings: 3-6 short sentences, each naming one concrete transformation you applied to the manifest and why (e.g. "Appended #/dl.7z for the NSIS installer", "Added autoupdate.hash from checksums.txt"). Only list transformations you actually made, in order. It must never be placed inside `result`.

   CORRECT (result is a string with escaped inner quotes, explanation and activities are separate parameters):
   ```
   finalize_task_result(result="{\"version\":\"8.6.1\",\"description\":\"...\",\"url\":\"...\",\"hash\":\"...\",\"innosetup\":true,...}", explanation="Used a portable zip with a 64bit url, kept the exe on PATH and added a Start Menu shortcut.", activities=["Appended #/dl.7z for the NSIS installer", "Added autoupdate.hash from checksums.txt"])
   ```

   WRONG (these fail because result must be a string, not an object):
   - `finalize_task_result(result={"version":"8.6.1",...})`  ← passed an object
   - `finalize_task_result(result={"status":"ok","manifest":{...}})`  ← wrapper object
   - `finalize_task_result(result={"result":{...}})`  ← nested key
   - `finalize_task_result(result={"version":"8.6.1","explanation":"..."})`  ← explanation must be a separate parameter, never inside the result manifest JSON
   - Returning text without calling the tool

## Rules

- For GUI apps with a main executable: add BOTH `bin` AND `shortcuts`. Only omit `bin` if the app has NO usable CLI interface (e.g. a system tray utility).
- `persist`: Keep the skeleton's `persist` entries as-is. They were derived from source-code analysis during research. Use for apps that store user config, themes, plugins, or data; some config-intensive CLI tools need it (e.g. bat, starship).

  **CRITICAL: persist path rules**
  - Persist entries MUST be `\${'$'}dir`-relative paths (e.g. `config`, `data`, `.cache`).
  - **FORBIDDEN**: tilde paths (`~/.cache/...`), absolute paths (`C:\...`, `/home/...`),
    environment variable references (`%APPDATA%`, `%USERPROFILE%`), or any path starting
    with `/` or `\\`.
  - If the app stores data in `%APPDATA%` or `%USERPROFILE%` (user profile directories),
    do NOT persist those paths - Scoop cannot manage them. Instead, add a `notes` entry
    documenting the data location, or use `env_set` to redirect the app to
    `\${'$'}persist_dir\<dir>` if the app supports configuration via environment variables.
  - The skeleton already follows these rules. Do NOT add illegal persist entries.
- `env_set`: only for app-specific env vars (BAT_CONFIG_DIR, etc.). Never set XDG_CONFIG_HOME, HOME, APPDATA.
- License must be an SPDX identifier, never default to "Proprietary" for a public GitHub repository.
- NEVER end your turn with only reasoning text. Always follow with `finalize_task_result`.
- NEVER restructure or reorganize the skeleton JSON. Copy it as-is and only add missing fields.
- ⚠️ NEVER change `checkver` format — if the skeleton has `{"github":"https://..."}`, copy it exactly. Do NOT replace with `"github"`.
- NEVER move `extract_dir` between top-level and architecture blocks — keep it wherever the skeleton put it.
- NEVER add or remove the `architecture` wrapper inside `autoupdate` — keep the skeleton's structure.
- NEVER add `extract_dir` inside `autoupdate` if the skeleton does not have it there — static `extract_dir` is already at the manifest top level."""
}

/**
 * Builds the prompt for the ASSIST phase (user follow-up, explanatory = true).
 *
 * The LLM receives an [AssistContext] containing the current manifest and the
 * user's instruction. Besides finalize_task_result it may get tools
 * (verify_install when [installTestActive], save_to_bucket when [saveActive]).
 * The prompt focuses the LLM on applying the user's requested modification, and
 * additionally provides a Question Escape Hatch: if the user message is a pure
 * question about the manifest (no change request), the LLM answers via the
 * `explanation` parameter and returns the manifest verbatim (see
 * [com.scoopai.core.llm.KoogCompat.finishTool] `answered` flag).
 *
 * When [installTestActive] is true the assist subgraph exposes the `verify_install`
 * tool: run it only when the user explicitly asks, in its own turn, then hand the
 * fixed manifest back via `finalize_task_result` (the verify phase re-tests).
 * When false the prompt honestly states that no install test ran; with a
 * non-null [assistTestUnavailableReason] it also states why install testing is
 * unavailable so the model can relay the root cause to the user.
 *
 * When [saveActive] is true the assist subgraph exposes the `save_to_bucket`
 * tool backed by the host's save callback: call it only when the user explicitly
 * asks to save/bucket the manifest.
 *
 * When [installActive] is true the assist subgraph also exposes the `install_app`
 * tool backed by the host's install callback: call it only when the user
 * explicitly asks to install, either from the saved bucket manifest or directly
 * from the current unsaved manifest (bucket omitted).
 *
 * When [readRefActive] is true the assist subgraph also exposes the
 * `read_manifest_reference` tool backed by the host's local-manifest read
 * callback: call it when the user references an existing manifest via the
 * `@bucket/app` syntax (or explicitly asks to read one), and use the returned
 * content as a base for edits or as a style reference.
 */
fun buildAssistPrompt(
    ctx: AssistContext,
    installTestActive: Boolean = false,
    saveActive: Boolean = false,
    installActive: Boolean = false,
    assistTestUnavailableReason: String? = null,
    readRefActive: Boolean = false,
): String =
    buildAssistKernel(
        ctx,
        opening = "You are a Scoop manifest assistant.",
        includingEscape = true,
        installTestActive = installTestActive,
        saveActive = saveActive,
        installActive = installActive,
        assistTestUnavailableReason = assistTestUnavailableReason,
        readRefActive = readRefActive,
 )

/**
 * Builds the prompt for the REPAIR phase (internal fix loop, explanatory = false).
 *
 * Same shared kernel as [buildAssistPrompt] but without the Question Escape Hatch;
 * the internal repair loop is never a pure-question follow-up, so it must not be
 * told about the `answered` escape-hatch parameter. It also never gets the install
 * test honesty section: the repair loop is not user-facing. When [saveActive] is
 * true the schema still advertises save_to_bucket, so the prompt explicitly bans it.
 * The same applies to [installActive] and the install_app tool.
 */
fun buildRepairPrompt(
    ctx: AssistContext,
    saveActive: Boolean = false,
    installActive: Boolean = false,
    readRefActive: Boolean = false,
): String =
    buildAssistKernel(
        ctx,
        opening = "You are a Scoop manifest repairer.",
        includingEscape = false,
        saveActive = saveActive,
        installActive = installActive,
        readRefActive = readRefActive,
    )

/**
 * Shared kernel for the assist and repair prompts. The two callers differ only in
 * the opening identity line and the presence of the Question Escape Hatch; the
 * dl-7z / NSIS / evidence / name-conflict / explanation sections are written once
 * here so they cannot drift apart.
 */
private fun buildAssistKernel(
    ctx: AssistContext,
    opening: String,
    includingEscape: Boolean,
    installTestActive: Boolean = false,
    saveActive: Boolean = false,
    installActive: Boolean = false,
    assistTestUnavailableReason: String? = null,
    readRefActive: Boolean = false,
): String = buildString {
    appendLine("⚠️ **CRITICAL: Do NOT change `version` or `url` to a different release.** The version was fixed during the analyze phase. If a listed issue cannot be fixed without changing version, LEAVE it unfixed — better to ship with a warning than to downgrade the version.")
    appendLine()
    appendLine("$opening Modify the manifest according to the instructions and feedback below.")
    appendLine()
    if (ctx.history.isNotEmpty()) {
        appendLine("## Conversation / Assist History")
        ctx.history.forEachIndexed { idx, item ->
            appendLine("${idx + 1}. $item")
        }
        appendLine()
        appendLine("## Current Request / Issues to Fix")
        appendLine(ctx.feedback)
    } else {
        appendLine("## Issues to Fix")
        appendLine(ctx.feedback)
    }
    appendLine()
    if (!ctx.evidence.isNullOrBlank()) {
        appendLine("## Detected Package & Extraction Facts (Evidence)")
        appendLine("These facts were gathered directly from inspecting the release archive and installers during earlier phases:")
        appendLine(ctx.evidence.trim())
        appendLine()
    }
    appendLine("## Current Manifest")
    appendLine("```json")
    appendLine(ctx.manifest)
    appendLine("```")
    appendLine()
    appendLine("## Rules")
    appendLine("- Fix each listed issue or apply the user's requested modifications accurately.")
    appendLine("- If the instruction explicitly requests adding, modifying, or removing specific fields (such as shortcuts, bin, suggest, notes, persist, description), apply those modifications faithfully.")
    appendLine("- Preserve all other unrelated fields and the existing structure verbatim.")
    appendLine("- Do NOT reorganize or rewrite unrelated parts of the manifest.")
    appendLine("- After fixing/refining, call `finalize_task_result` with the corrected manifest.")
    if (ctx.explanatory) {
        appendLine("- When calling `finalize_task_result`, also pass the optional `explanation` parameter: directly answer the user's specific request by stating which fields were added, removed, or modified and why, in the same language as the user's instruction.")
        appendLine("  Keep it at most 3 short sentences, about 60 words total; the manifest is displayed in the UI, so do not recap the changed fields one by one.")
    }
    appendLine("- When you modified the manifest, also pass the optional `activities` parameter: a list of 3-6 short sentences, each naming one concrete change you made and why (e.g. \"Removed the %APPDATA% persist path because Scoop forbids absolute user paths\", \"Added shortcuts because you asked for a Start Menu entry\"). Only list changes you actually made, in order; omit the parameter when the manifest is returned unchanged.")
    if (includingEscape) {
        appendLine()
        appendLine("## Question Escape Hatch")
        appendLine("If the user's message is a pure question about the manifest (no change request), do NOT modify the manifest. Answer in at most 3 short sentences (~60 words) in the user's language via the existing `explanation` parameter, set the Boolean parameter `answered` to true, and return the manifest verbatim in `result`. If the message asks for any change (add/remove/modify/rename fields, switch version or asset, fix a problem), `answered` must be false or omitted.")
    }
    if (ctx.explanatory) {
        appendLine()
        appendLine("## Install Test Status")
        if (installTestActive) {
            appendLine("You have the `verify_install` tool: it performs a REAL scoop install of the current manifest, then uninstalls and cleans up, returning JSON (`success`, `exit_code`, `error`, `output`).")
            appendLine()
            appendLine("### When to call it")
            appendLine("- ONLY when the user explicitly asks for an install test. Do NOT call it proactively or for every manifest.")
            appendLine("- Explicit install-test requests include phrasings like \"test install\", \"test install it\", \"run the install test\", or their equivalents in the user's language; such requests ALWAYS mean calling verify_install first.")
            appendLine("- Call it in its OWN separate turn: invoke `verify_install` alone, wait for its result, then decide the next step. Do NOT call it in the same turn as `finalize_task_result`.")
            appendLine("- After it returns, honestly report the outcome: if `success` is false, describe the failure (fix the manifest if you can); if `success` is true, say the install test passed.")
            appendLine("- If the test reveals a problem, fix the manifest and then call `finalize_task_result` to hand it over. The verify phase re-runs the install test automatically; do NOT run a second `verify_install` inside the assist subgraph.")
            appendLine("- If the user's message already contains install test failure details (error summary or log excerpt), do NOT call verify_install to reproduce the failure; fix the manifest directly from those details. The verify phase re-tests automatically.")
        } else {
            appendLine("No install test was performed for this manifest. Say so honestly if the user asks about installability; never claim the app was install-tested.")
            if (assistTestUnavailableReason != null) {
                appendLine("Install testing is unavailable in this session (reason: $assistTestUnavailableReason). If the user asks for an install test, tell them this reason and suggest checking that scoop is on PATH.")
            }
        }
    } else {
        // Repair loop: the verify phase owns install tests and re-runs them per round.
        appendLine("- Install testing is handled automatically by the verify phase. Do NOT call `verify_install` here.")
    }
    if (saveActive) {
        if (ctx.explanatory) {
            appendLine()
            appendLine("## Save to Bucket")
            appendLine("You have the `save_to_bucket` tool: it saves the current manifest into a local Scoop bucket and returns JSON (`success`, `path`, `bucket`, `name`, `error`, `needsOverwrite`, `needsBucket`).")
            appendLine("- Call it ONLY when the user explicitly asks to save or bucket the manifest. Do NOT call it proactively.")
            appendLine("- If save_to_bucket returns needsBucket:true with a buckets array, call __ask_user__ with ONE single-choice question listing exactly those buckets as options; then call save_to_bucket again with the chosen bucket.")
            appendLine("- Never complete a save without an explicit bucket: after a needsBucket result, do not retry save_to_bucket without a bucket.")
            appendLine("- If the user did not specify a bucket, ask them first (via the Question Escape Hatch: `finalize_task_result` with `answered=true` and the question in `explanation`). Once the user confirms a bucket, pass it explicitly; if they later ask to save again, reuse the same bucket again without asking.")
            appendLine("- If the save succeeded and the manifest did not change, call `finalize_task_result` with `answered=true`, returning the manifest verbatim in `result`; this ends the turn, no further verification.")
            appendLine("- If the manifest was both saved AND further changed, finalize with `answered=false`; only an unchanged save may use the `answered=true` shortcut.")
            appendLine("- After it returns, honestly report the outcome: state the saved path on success, or the error otherwise.")
            appendLine("- If the result contains `needsOverwrite: true`, the target file already exists. Ask the user whether to overwrite (via the Question Escape Hatch: `finalize_task_result` with `answered=true` and the question in `explanation`); once the user confirms, call `save_to_bucket` again with `overwrite=true`.")
            appendLine("- If the user reports that an installed app fails at runtime (crash on launch, missing features, wrong behavior), treat it as a manifest fix request: fix the manifest, call save_to_bucket again with the SAME bucket and overwrite=true, then reinstall the app from the bucket, and report the outcome.")
            appendLine("- In that fix-save-reinstall flow, the overwrite gate comes first: if the first re-save replies `needsOverwrite: true`, run the user confirmation flow above and only then save; never pass `overwrite=true` on the first re-save to bypass the confirmation. Only after the user has confirmed may later re-saves in the same loop pass `overwrite=true` directly.")
            appendLine("- This fix-save-reinstall loop may repeat up to two times before you report the remaining problem honestly.")
            appendLine("- If the reinstall step reports the app is already installed, do NOT retry the loop blindly: tell the user the fixed manifest is saved and takes effect only after the app is reinstalled (the Scooper host reinstalls automatically; on the CLI ask the user to run `scoop update --force`).")
            appendLine("- Runtime failures often stem from wrong bin paths, missing extract_dir, or incorrect shortcut targets; check those first when the install itself succeeded.")
            if (installActive) {
                appendLine("- For the reinstall step above, call `install_app` with the same bucket and name the manifest was saved under.")
                appendLine("- If install_app reports the app as already installed, do not retry; ask the user to uninstall it first, or explain that the fix requires a manual reinstall.")
            }
        } else {
            // Repair loop: saving mid-repair would persist an unverified manifest.
            appendLine("- Saving to a bucket is not part of the repair loop. Do NOT call `save_to_bucket` here.")
        }
    }
    if (installActive) {
        if (ctx.explanatory) {
            appendLine()
            appendLine("## Install App")
            appendLine("You have the `install_app` tool: it installs the app and returns JSON (`success`, `app`, `bucket`, `message`, `error`).")
            appendLine("- Call it ONLY when the user explicitly asks to install the app. Do NOT call it proactively.")
            appendLine("- Preferred path: if the manifest is not saved yet, call `save_to_bucket` first, then call `install_app` with the bucket and name the manifest was saved under.")
            appendLine("- Direct path: when the user explicitly asks to install right away without saving (e.g. \"just install it\", \"don't save to a bucket yet\"), call `install_app` with only `name` (omit `bucket`). The current manifest is installed directly from its local file; the app then has no bucket affiliation and can be saved to a bucket later.")
            appendLine("- After it returns, honestly report the outcome, including when the app is already installed.")
        } else {
            // Repair loop: installing mid-repair would act on an unverified manifest.
            appendLine("- Installing the app is not part of the repair loop. Do NOT call `install_app` here.")
        }
    }
    if (readRefActive) {
        if (ctx.explanatory) {
            appendLine()
            appendLine("## Read Manifest Reference")
            appendLine("You have the `read_manifest_reference` tool: it reads the raw JSON of an existing manifest from a local Scoop bucket and returns it with metadata (bucket, name, path).")
            appendLine("- Call it when the user references an existing manifest with the `@bucket/app` syntax, or explicitly asks you to read a local manifest. `@main/golang` means bucket=`main`, name=`golang`; pass those as the two tool parameters (trimmed).")
            appendLine("- Call it when the referenced manifest is the modification target or a reference worth consulting; do not call it proactively for manifests nobody mentioned.")
            appendLine("- Use the returned content as the base for the requested edits, or as a style/pattern reference; never claim you read a manifest you did not actually read.")
            appendLine("- If the result reports `success: false`, the manifest does not exist or cannot be read: tell the user, do not guess its content, and suggest checking the bucket/app spelling.")
        } else {
            // Repair loop: reading references mid-repair is out of scope.
            appendLine("- Reading existing manifests is not part of the repair loop. Do NOT call `read_manifest_reference` here.")
        }
    }
    appendLine()
    appendLine("## Verify Facts with Read-only Network Tools")
    appendLine("You have these read-only network tools for fact checking:")
    appendLine("- `parseGitHubUrl`: extract owner and repo from a GitHub URL")
    appendLine("- `fetchRepoInfo`: repository description, license, homepage, stars")
    appendLine("- `fetchLatestRelease`: latest release tag, assets and their hashes")
    appendLine("- `fetchAllReleases`: recent releases (stable vs pre-release channels)")
    appendLine("- `listReleaseAssets`: assets of one specific release")
    appendLine("- `fetchFileContent`: read a file from the repository (LICENSE, README, etc.)")
    appendLine("- `fetchWebPage`: fetch a web page as plain text (budget: ${ToolCallBudget.FETCH_WEB_PAGE_LIMIT} calls)")
    appendLine("- `fetchJson`: fetch a URL and return the raw JSON")
    appendLine("- `resolveRedirect`: follow redirects and return the final URL (budget: ${ToolCallBudget.RESOLVE_REDIRECT_LIMIT} calls)")
    appendLine("When the user asks about a fact you can look up (repository description, license, latest version, release assets, homepage), call the matching tool; do not rely on memory for facts you can verify. Also use them to check a proposed edit before applying it (confirm a license identifier or a newer release first). These tools are read-only: they never download for hashing and never modify local files. When you state a verified fact, cite the source (repository or page URL) in your reply. Do not call them for questions the current manifest and evidence already answer, and do not call other research tools (computeSha256FromUrl, listArchiveContents, searchTextInArchive); they are not available here.")
    appendLine()
    appendLine("## Name Conflict Resolution")
    appendLine("If the issue mentions an app name conflict with an existing Scoop bucket manifest, you MUST:")
    appendLine("1. Choose a new, unique name (e.g. prefix with the repo name or GitHub owner).")
    appendLine("2. Update `suggested_app_name` in the manifest path accordingly.")
    appendLine("3. Avoid common single-word names like `micro`, `app`, `tool`, etc.")
    appendLine()
    appendLine("## NSIS `#/dl.7z` Extraction Context")
    appendLine("- If an asset URL already contains the `#/dl.7z` fragment, Scoop extracts the installer's inner payload with 7-Zip before installation, so any executable referenced by `bin` or `shortcuts` DOES exist after extraction. A review issue claiming a bin/shortcut target \"will not exist after extracting\" for such an URL is a **false positive** — keep the manifest unchanged and explain this in your reply.")
    appendLine("- If the asset is genuinely an installer-bearing zip WITHOUT `#/dl.7z`, fix it by either appending `#/dl.7z` to the url or switching to a portable (non-installer) asset.")
    appendLine("- GUI-only apps may legitimately omit `bin` and keep only `shortcuts`; do not add a `bin` field just because a review issue mentions a missing bin.")
    appendLine()
    appendLine("## Attempt")
    append("attempt ${ctx.retryCount}")
}
