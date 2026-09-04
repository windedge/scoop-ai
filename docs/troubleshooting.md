# Troubleshooting

This guide covers common issues you may encounter when using scoop-ai.
Before diving into specific problems, run the diagnostic command first:

```bash
scoop-ai doctor
```

It checks 7 aspects of your environment (JDK version, OS, Scoop installation,
API key, cache directory, write permissions, and scoop-ai version) and reports
any failures or warnings. This is the fastest way to identify configuration
problems.

If `scoop-ai doctor` reports all checks passed but you still see an error,
look up the exit code and symptom below.

---

## Exit Code 2 (USAGE) -- Invalid command-line arguments

**Symptoms**:
- "Error: missing argument <url>"
- "Invalid value for --persist: 'invalid-mode'"
- "Option --model requires a value"

**Cause**:
- No URL was provided to the `generate` command.
- A choice option received an unsupported value (e.g. `--persist bad`).
- A flag that requires a value was specified without one.

**Fix**:
- Always provide a URL: `scoop-ai generate https://github.com/user/repo`
- Use only valid persist modes: `auto`, `force`, `none`, `no`
- Check that options expecting values have them (e.g. `--model gpt-4o`)
- Run `scoop-ai generate --help` to see all valid options.

**See also**: `scoop-ai doctor`, Exit code 3 (CONFIG)

---

## Exit Code 3 (CONFIG) -- Configuration error

**Symptoms**:
- "Error: --base-url is required for openai-compatible provider"
- "Please set DEEPSEEK_API_KEY environment variable or pass --api-key"

**Cause**:
- The `openai-compatible` provider was selected but no `--base-url` was given.
- The API key resolution failed: no CLI `--api-key` and no matching env var.

**Fix**:
- For `openai-compatible`, always provide `--base-url` or set `SCOOP_AI_BASE_URL`.
- Set the provider-specific API key env var (e.g. `DEEPSEEK_API_KEY`) or the
  fallback `SCOOP_AI_API_KEY`. See docs/providers.md.
- Verify with `scoop-ai config show` that all values are resolved correctly.

**See also**: `scoop-ai doctor`, `scoop-ai config show`, docs/providers.md

---

## Exit Code 64 (NO_ASSETS) -- No Windows assets found

**Symptoms**:
- "No Windows assets found for the given project."

**Cause**:
- The GitHub repository has no Windows releases (no `.exe`, `.msi`, `.zip`).
- The release assets are named with non-standard suffixes that the research
  phase did not recognise.
- The project is a library or tool that does not ship Windows binaries.

**Fix**:
- Visit the project's GitHub Releases page and confirm a Windows asset exists.
- If the asset is present with a non-standard name, use `--prompt` to guide the
  agent, or supply a direct download URL.
- If the project has no Windows releases at all, scoop-ai cannot generate a
  useful manifest.

**See also**: Exit code 70 (IO_ERROR)

---

## Exit Code 65 (LLM_ERROR) -- LLM provider failure

**Symptoms**:
- "LLM request failed: Connect timeout: 30s elapsed"
- "LLM request failed: 401 Unauthorized"
- "LLM request failed: 429 Too Many Requests"
- "LLM request failed: 503 Service Unavailable"

**Cause**:
- Network connectivity issue: the base URL is unreachable.
- API key is invalid, expired, or belongs to a different provider.
- Rate limiting: too many requests in a short period.
- The provider service is temporarily down.

**Fix**:
- Check your network and proxy settings. For company proxies, set `HTTPS_PROXY`.
- Verify the API key: run `scoop-ai doctor` and check the masked prefix.
- Wait for rate limits to reset, or use a different API key / provider.
- For `openai-compatible`, verify that `--base-url` points to the correct
  endpoint (the agent appends `v1/chat/completions` automatically).

**See also**: `scoop-ai doctor`, docs/providers.md, ProxyConnection FAQ

---

## Exit Code 66 (VERIFY_FAILED) -- Manifest verification failed

**Symptoms**:
- "Tool execution failed: scoop install verification failed"
- "ERROR: scoop install exited with code 1"

**Cause**:
- The generated manifest contains invalid fields (wrong URL, missing version).
- The download URL is unreachable or the file hash does not match.
- Scoop's installation process fails for a reason not caught by manifest
  validation alone (e.g. the installer expects admin privileges).

**Fix**:
- Inspect the generated manifest in `bucket/<app>.json`.
- Compare with the official Scoop bucket manifest if one exists:
  `C:\Users\<user>\scoop\buckets\main\bucket\<app>.json`
- Manually adjust `url`, `hash`, `bin`, or `installer` fields.
- Run `scoop install <app>` to confirm the fix.
- If the issue is transient (network), re-run without `--no-cache`.

**See also**: `scoop-ai doctor`, Exit code 70 (IO_ERROR), VerifyFailed FAQ

---

## Exit Code 70 (IO_ERROR) -- Network or file I/O error

**Symptoms**:
- "I/O failure: Connection refused"
- "I/O failure: <path> (The system cannot find the file)"
- "I/O failure: scoop cache directory is not writable"

**Cause**:
- Network I/O: the GitHub API or asset download server is unreachable.
- File I/O: the output directory does not exist or is not writable.
- Cache I/O: the Scoop cache directory (`~/scoop/cache`) is inaccessible.

**Fix**:
- Check network connectivity and proxy configuration.
- Ensure the output directory exists: `mkdir bucket`
- Run `scoop-ai doctor` to verify the cache directory and write permissions.
- Use `--output <path>` to write to a different location.

**See also**: `scoop-ai doctor`

---

## Q: "No Windows assets found" for a project that has Windows releases

**Symptoms**:
- Exit code 64 (NO_ASSETS).
- The project definitely has `.exe` or `.zip` files on its Releases page.

**Cause**:
- The research phase filters assets by MIME type or extension. Some projects
  name their assets unconventionally (e.g. `myapp_windows_amd64.tar.gz` without
  a recognised Windows extension).
- The release may be a "pre-release" which is skipped by default.

**Fix**:
- Run with `--prompt "The Windows asset is named myapp_windows_amd64.tar.gz"`
  to hint the agent.
- Provide a direct download URL as the argument instead of the repository URL.
- If the asset truly is a `.tar.gz`, the manifest still works (Scoop handles
  archive extraction) -- it just needs to be identified correctly.

**See also**: Exit code 64 (NO_ASSETS)

---

## Q: LLM connection timeout

**Symptoms**:
- "LLM request failed: Connect timeout: 30s elapsed"
- The process hangs for 30 seconds then fails with exit code 65.

**Cause**:
- The default 30-second TCP connect timeout was exceeded.
- The LLM provider base URL is incorrect or unreachable.
- A corporate firewall or proxy is blocking the connection.
- The provider's server is slow or overloaded.

**Fix**:
- Check the base URL: ensure `SCOOP_AI_BASE_URL` or `--base-url` is correct.
- For corporate networks, set `HTTPS_PROXY` or `HTTP_PROXY` environment
  variables.
- Try a different provider (e.g. `openai` instead of `deepseek`) to isolate
  the issue.
- Run `scoop-ai doctor` to confirm the API key is set.

**See also**: `scoop-ai doctor`, docs/providers.md, ProxyConnection FAQ

---

## Q: "401 Unauthorized" or "403 Forbidden" from the LLM provider

**Symptoms**:
- "LLM request failed: 401 Unauthorized"
- "LLM request failed: 403 Forbidden"
- Exit code 65 (LLM_ERROR).

**Cause**:
- API key is invalid, expired, or revoked.
- API key is set but for the wrong provider (e.g. an OpenAI key used with
  DeepSeek's endpoint).
- The provider's API has access restrictions (e.g. not enough credits).

**Fix**:
- Run `scoop-ai doctor` to confirm an API key is found. Check the masked
  prefix matches your key.
- Verify that the env var matches the provider:
  - `DEEPSEEK_API_KEY` for provider `deepseek`
  - `OPENAI_API_KEY` for provider `openai`
  - `SCOOP_AI_API_KEY` works as a fallback for all providers.
- If using `openai-compatible`, confirm the API key works with the custom
  endpoint.
- Generate a new key from the provider's dashboard if expired.

**See also**: `scoop-ai doctor`, `scoop-ai config show`, docs/providers.md

---

## Q: GitHub API rate limiting

**Symptoms**:
- "HTTP 403" during the research phase.
- The agent fails early when querying the GitHub Releases API.

**Cause**:
- No `GITHUB_TOKEN` is set. Unauthenticated GitHub API requests are limited
  to 60 per hour.
- The token is set but lacks the necessary permissions.
- Multiple rapid requests exhausted the rate limit.

**Fix**:
- Set `GITHUB_TOKEN` (or `GH_TOKEN`) environment variable with a personal
  access token (classic or fine-grained, with `public_repo` scope).
- Or pass `--github-token <token>` to the generate command.
- Wait for the rate limit window to reset (usually 1 hour).
- Verify with `scoop-ai config show` -- "github-token: set" confirms it is
  detected.

**See also**: `scoop-ai config show`

---

## Q: "scoop install" cannot find the cached file

**Symptoms**:
- After generating a manifest, `scoop install <app>` tries to download the
  file again instead of reusing the cached copy.
- Exit code 66 (VERIFY_FAILED) if the install verification ran.

**Cause**:
- The `--no-cache` flag was used, which discards downloaded files after
  generation.
- The cache file was deleted manually or by a cleanup tool.
- The manifest's `url` field uses a different URL than what was cached.

**Fix**:
- Do not use `--no-cache` if you plan to install immediately after generation.
- Re-run the generation without `--no-cache` to restore the cache.
- Check the Scoop cache directory: `~/scoop/cache/` should contain a file
  named `<app>#<version>#<hash>.download`.
- If the cache was cleaned, just re-run `scoop install` -- it will download
  again.

**See also**: `scoop-ai doctor` (cache-dir check), Exit code 66 (VERIFY_FAILED)

---

## Q: Manifest verification failed (scoop install error)

**Symptoms**:
- "ERROR: scoop install exited with code 1" during install verification.
- Exit code 66 (VERIFY_FAILED).

**Cause**:
- The generated manifest has errors that Scoop rejects:
  - `version` field formatting is invalid.
  - `url` points to a non-existent or inaccessible file.
  - `hash` does not match the downloaded file.
  - `bin` references an executable that does not exist in the archive.
  - `checkver` or `autoupdate` have incorrect patterns.

**Fix**:
- Open the generated manifest in `bucket/<app>.json`.
- Compare with the official Scoop bucket manifest:
  - Main: `C:\Users\<user>\scoop\buckets\main\bucket\<app>.json`
  - Extras: `C:\Users\<user>\scoop\buckets\extras\bucket\<app>.json`
- Common differences to check:
  - `bin`: should list only the main executables, not helper DLLs or scripts.
  - `license`: should use the correct SPDX identifier (e.g. `MIT`, not `MIT
    license`).
  - `extract_dir`: the subdirectory inside the archive where files are.
  - `shortcuts`: for GUI apps, the expected Start Menu shortcuts.
- Fix the manifest manually, then run `scoop install <app>.json` to confirm.

**See also**: Exit code 66 (VERIFY_FAILED)

---

## Q: persist field is missing or incorrect

**Symptoms**:
- After installation with `scoop install`, application settings are not
  preserved across updates.
- Exit code 66 (VERIFY_FAILED) when install verification reports persist issues.

**Cause**:
- The `auto` persist mode (default) relies on the research phase to discover
  config directories from source code or documentation. This heuristic may
  miss some directories.
- The `none` persist mode explicitly removes all persist directives.

**Fix**:
- Use `--persist force` to tell the agent to include a persist field even if
  discovery is uncertain.
- Examine the generated manifest; if `persist` is missing or incomplete, add
  the correct paths manually.
- Common persist targets for GUI apps: `config`, `data`, `profile`, `settings`
  subdirectories inside the app's installation directory.
- Re-run generation with a hint: `--prompt "The app stores config in
  %APPDATA%\\<AppName>"`

**See also**: Exit code 66 (VERIFY_FAILED)

---

## Q: Connection fails behind a corporate proxy

**Symptoms**:
- LLM API calls time out or return "Connection refused".
- GitHub API requests fail.
- All network operations fail, but browser works (because it uses system proxy).

**Cause**:
- The JVM does not automatically use system proxy settings.
- `HTTP_PROXY` / `HTTPS_PROXY` environment variables are not set, or are set
  incorrectly.

**Fix**:
- Set the proxy environment variables before running scoop-ai:
  ```bash
  # Windows CMD
  set HTTPS_PROXY=http://proxy.company.com:8080
  java -jar cli.jar ...

  # PowerShell
  $env:HTTPS_PROXY="http://proxy.company.com:8080"
  java -jar cli.jar ...

  # bash (Git Bash or WSL)
  export HTTPS_PROXY=http://proxy.company.com:8080
  java -jar cli.jar ...
  ```
- If the proxy requires authentication, include it in the URL:
  `http://user:pass@proxy.company.com:8080`
- Use `scoop-ai doctor` to confirm the API key is detected, then re-run.

**See also**: `scoop-ai doctor`, Exit code 65 (LLM_ERROR), Exit code 70 (IO_ERROR)

---

## Q: ANSI escape codes in help output / Windows cmd garbled text

**Symptoms**:
- Running `scoop-ai --help` in Windows Command Prompt shows garbled text with
  `[1m`, `[4m` sequences.
- Colours and bold formatting display as literal escape codes.

**Cause**:
- The clikt library outputs ANSI escape codes for formatting.
- Windows Command Prompt (cmd.exe) does not natively interpret ANSI escape
  sequences (unlike Windows Terminal or modern PowerShell).

**Fix**:
- Use Windows Terminal, PowerShell 7+, or a terminal emulator that supports
  ANSI (e.g. ConEmu, Cmder).
- Set `NO_COLOR=1` environment variable to disable all ANSI formatting:
  ```cmd
  set NO_COLOR=1
  java -jar cli.jar --help
  ```
- Or use `--no-progress` flag (this only disables the progress overlay, not
  all ANSI output).

**See also**: `--no-progress` flag description in CLI help

---

## Q: Generated manifest differs from official Scoop bucket

**Symptoms**:
- The generated `bucket/<app>.json` has different fields compared to the
  official manifest in `main` or `extras` bucket.
- `bin` lists extra helper executables that should not be in PATH.
- `license` uses a different SPDX identifier.
- `extract_dir` is missing or incorrect.

**Cause**:
- The LLM agent makes its best guess based on the archive contents and project
  metadata. Official Scoop manifests are manually curated and may include
  nuances that the agent cannot infer automatically.
- `bin` may include all executables found in the archive, including helper
  tools that are not meant to be on PATH.
- `license` may be inferred from the GitHub repo's "License" field which does
  not always match the actual license of the distributed binaries.

**Fix**:
- Compare the generated manifest with the official one:
  ```bash
  diff -u bucket/<app>.json /path/to/official/bucket/<app>.json
  ```
- Pay special attention to:
  - `bin`: remove helper executables, keep only the main binary(ies).
  - `license`: confirm the SPDX identifier (`-only` / `-or-later` suffixes).
  - `extract_dir`: set if the archive has a root subdirectory.
  - `shortcuts`: add for GUI applications.
  - `persist`: add any data/config directories.
- Re-run with `--prompt` to guide the agent:
  `--prompt "Only include \<main-app\> in bin. License is GPL-3.0-only."`
- Once the manifest is correct, it is safe to manually edit the JSON file.

**See also**: Exit code 66 (VERIFY_FAILED), docs/providers.md
