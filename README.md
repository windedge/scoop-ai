# scoop-ai

AI-powered Scoop manifest generator for Windows packages. Given a GitHub repository URL (or project page), it automatically researches the project, analyzes Windows assets, and generates a standards-compliant [Scoop](https://scoop.sh/) bucket manifest.

## Installation

> **NOTE**: `bucket/scoop-ai.json` is currently a template. The `url`, `hash`,
> and `version` fields are placeholders until the first GitHub Release is published.
> Running `scoop install bucket/scoop-ai.json` now will fail. Until a release is
> cut, use `java -jar` directly as described in Run.

```bash
# Install from local bucket manifest (requires JDK 17+)
scoop install bucket/scoop-ai.json

# Or via direct URL once published
scoop install https://github.com/placeholder/scoop-ai/releases/download/v0.1.0/cli-0.1.0-all.jar
```

The manifest bundles scoop-ai as a fat JAR with all dependencies. Scoop creates
a `scoop-ai.bat` shim that invokes `java -jar scoop-ai.jar`.

**Prerequisites**:
- [Scoop](https://scoop.sh/) package manager
- JDK 17+ (`scoop install temurin17-jdk`)

> **Note**: The Scoop manifest is self-bootstrapping: `bucket/scoop-ai.json` is
> the first manifest generated for scoop-ai itself. Once published to GitHub
> Releases, the `checkver` / `autoupdate` fields will keep it up to date.

## Architecture

scoop-ai uses a **three-phase LLM agent workflow** built on [Koog](https://github.com/JetBrains/koog):

```
Research -> Analyze -> Generate
  |           |          |
  v           v          v
GitHub API   Hash      Validate
Web Fetcher  Archive    Write
             Inspector
```

| Phase   | Purpose                                    | Tools                                      |
|---------|--------------------------------------------|--------------------------------------------|
| Research | Gather project info, find Windows assets  | GitHub API, Web Fetcher, URL Parser       |
| Analyze  | Compute hashes, inspect archives          | SHA-256 Hasher, ZIP Inspector              |
| Generate | Create manifest, validate, write          | Manifest Validator, File Writer, Verifier |

The project follows a **Gradle multi-module** structure:

```
core/   - Business logic, agent strategy, prompts, configuration
tools/  - Tool implementations (HTTP, hashing, archive, manifest I/O)
cli/    - Command-line entry point
```

Dependency direction: `cli -> core + tools`, `tools -> core`, `core -> Koog framework`.

## Build

```bash
# Build all modules
./gradlew build

# Build fat JAR
./gradlew :cli:shadowJar

# Run tests
./gradlew :core:jvmTest
./gradlew :cli:test
```

## Run

```bash
# Generate a manifest from a GitHub URL (uses DeepSeek by default)
java -jar cli/build/libs/cli-0.1.0-all.jar https://github.com/sharkdp/fd

# Or via Gradle
./gradlew :cli:run --args="https://github.com/sharkdp/fd"
```

## CLI Usage

### Commands

scoop-ai uses a clikt-based subcommand architecture:

| Command                       | Description                                              |
|-------------------------------|----------------------------------------------------------|
| `scoop-ai <url> [options]`   | Generate a Scoop manifest (URL routing, see below)       |
| `scoop-ai generate <url>`    | Generate a Scoop manifest (canonical form)               |
| `scoop-ai doctor`            | Run environment diagnostics (7 health checks)            |
| `scoop-ai config show`       | Display runtime configuration (env vars + defaults)      |
| `scoop-ai completion <shell>`| Generate shell completion scripts                        |
| `scoop-ai version`           | Show version information                                 |

URL routing: if the first argument is not one of the subcommand names and does not
start with `--`, it is treated as a URL and `generate` is automatically prepended.
Both `scoop-ai https://github.com/sharkdp/fd` and `scoop-ai generate https://github.com/sharkdp/fd` work identically.

Global options:

| Short | Long          | Description                                        |
|-------|---------------|----------------------------------------------------|
| `-h`  | `--help`      | Show help message and exit                         |
|       | `--version`   | Show version information and exit                  |

### `generate` Command

`scoop-ai generate <url> [options]`

#### Arguments

| Argument | Description                        |
|----------|------------------------------------|
| `<url>`  | GitHub repository URL or project URL |

#### Options (18 options)

| Short | Long               | Type     | Default                 | Description                                       |
|-------|--------------------|----------|-------------------------|---------------------------------------------------|
| `-o`  | `--output`         | `String` | `bucket/<app>.json`     | Output file path                                  |
| `-P`  | `--provider`       | `String` | `deepseek`              | LLM provider (`deepseek`, `openai`, `anthropic`, `ollama`, `openrouter`, `openai-compatible`) |
|       | `--api-key`        | `String` | env                     | LLM API key (overrides provider-specific env var) |
|       | `--base-url`       | `String` | env                     | Custom API base URL (required for `openai-compatible`) |
| `-m`  | `--model`          | `String` | provider default        | LLM model name override                           |
| `-V`  | `--verify`         | flag     | `false`                 | Run `scoop install` to verify the generated manifest |
|       | `--app-name`       | `String` | inferred from URL       | Application name for output path and cache        |
|       | `--cache`          | choice   | `keep`                  | Cache mode: keep\|discard (default: keep)       |
| `-p`  | `--prompt`         | `String` | `null`                  | Additional instructions for manifest generation   |
| `-n`  | `--non-interactive`| flag     | `false`                 | Skip interactive prompts (for batch/CI mode)      |
|       | `--github-token`   | `String` | env                     | GitHub personal access token (avoids rate limits) |
|       | `--output-format`  | choice   | `text`                  | Output format: text\|json (json prints structured ManifestResult) |
|       | `--artifacts`      | choice   | `auto`                  | Write phase artifacts: auto\|always\|never (default: auto) |
|       | `--run-id`         | `String` | auto-generated UUID     | Override run ID for artifacts and checkpoint resume |
|       | `--from`           | choice   | (none)                  | Resume from stage: analyze\|generate (requires `--run-id`) |
| `-s`  | `--persist`        | choice   | `auto`                  | Persist field policy: `auto`, `force`, `none`, `no` |
|       | `--debug`          | flag     | `false`                 | Print debug information including stack traces    |
|       | `--no-progress`    | flag     | `false`                 | Disable TTY progress overlay                      |

CLI flags take precedence over environment variables.

### `doctor` Command

`scoop-ai doctor`

Run 7 health checks to diagnose environment and configuration issues:

| # | Check             | Description                                       |
|---|-------------------|---------------------------------------------------|
| 1 | `jdk-version`     | JVM version (>= 17 required, >= 21 recommended)   |
| 2 | `os`              | Operating system name and version                 |
| 3 | `scoop-installed` | Scoop installation (`scoop --version`)            |
| 4 | `api-key`         | API key configured (shows masked prefix only)     |
| 5 | `cache-dir`       | Scoop cache directory exists and is writable      |
| 6 | `write-permission`| Current directory is writable                     |
| 7 | `version`         | scoop-ai version and commit hash                  |

Example output:

```
[OK  ] jdk-version: Java 17.0.14 (>= 17)
[OK  ] os: Windows 11 10.0
[WARN] scoop-installed: Scoop not found or not working (exit code 1)
[OK  ] api-key: Found in $SCOOP_AI_API_KEY (sk-a...bc12)
[OK  ] cache-dir: C:\Users\user\scoop\cache (writable)
[OK  ] write-permission: Current directory is writable
[OK  ] version: scoop-ai 0.1.0 (commit a1b2c3d)

0 failure(s), 1 warning(s), 6/7 checks passed
```

Exit code: 0 (all passed or warnings only), 1 (one or more failures).

If you encounter issues, see [docs/troubleshooting.md](docs/troubleshooting.md)
for a comprehensive FAQ covering common error scenarios.

### `config show` Command

`scoop-ai config show`

Display current runtime configuration after merging environment variables and
defaults. API keys and tokens are shown as `set` or `unset` -- never the actual
value.

Example output:

```
Runtime Configuration (env vars + defaults):

  provider:           deepseek
  model:              deepseek-v4-flash
  base-url:           default (not set)
  api-key:            set
  github-token:       unset
  persist-mode:       auto
  verify:             false
  non-interactive:    false
  cache-enabled:      true
  debug:              false

Note: This is the runtime configuration from environment variables and defaults.
It is NOT a persisted config file. Use environment variables to configure.
Pass --help to the generate subcommand for CLI flag documentation.
```

**Important**: This is a runtime-only view. There is no persistent configuration file yet
(planned for v0.4.0). All configuration comes from CLI flags and environment variables.

### `completion` Command

`scoop-ai completion <shell>`

Generate shell completion scripts for the specified shell.

| Shell        | Installation snippet                                          |
|--------------|---------------------------------------------------------------|
| **bash**     | `eval "$(scoop-ai completion bash)"`                          |
| **zsh**      | `source <(scoop-ai completion zsh)`                           |
| **fish**     | `scoop-ai completion fish \| source`                          |
| **powershell**| `scoop-ai completion powershell \| Out-String \| Invoke-Expression` |

For permanent installation, redirect the output to your completion directory:

```bash
# bash
scoop-ai completion bash > /usr/local/share/bash-completion/completions/scoop-ai

# powershell
scoop-ai completion powershell >> $PROFILE
```

### `version` Command

`scoop-ai version` or `scoop-ai --version`

Print version information and exit.

```
scoop-ai 0.1.0 (commit a1b2c3d)
```

## Environment Variables

> **WARNING**: API keys appear in your shell history and `ps aux` output.
> Prefer environment variables (via `direnv` or OS keyring) over `--api-key`.
> Run `scoop-ai doctor` to verify your setup without leaking keys.
> Keyring integration is planned for v0.4.0 (see roadmap).

See [docs/providers.md](docs/providers.md) for provider-specific configuration details
and [docs/troubleshooting.md](docs/troubleshooting.md) for common setup issues.

### Sensitive Variables (masked in all output)

These variables contain credentials and are never printed in clear text.
They appear as `set` or `unset` in diagnostic output.

| Variable               | Description                              |
|------------------------|------------------------------------------|
| `SCOOP_AI_API_KEY`     | API key (fallback for all providers) **(sensitive)** |
| `DEEPSEEK_API_KEY`     | DeepSeek provider API key **(sensitive)** |
| `OPENAI_API_KEY`       | OpenAI provider API key **(sensitive)**   |
| `ANTHROPIC_API_KEY`    | Anthropic provider API key **(sensitive)**|
| `OPENROUTER_API_KEY`   | OpenRouter provider API key **(sensitive)** |
| `GITHUB_TOKEN`         | GitHub personal access token **(sensitive)** |
| `GH_TOKEN`             | GitHub token alias **(sensitive)**        |

### Non-Sensitive Variables

| Variable               | Description                              | Default    |
|------------------------|------------------------------------------|------------|
| `SCOOP_AI_PROVIDER`    | LLM provider                             | `deepseek` |
| `SCOOP_AI_BASE_URL`    | Custom API base URL                      | --         |
| `SCOOP_AI_MODEL`       | LLM model name                           | provider default |
| `SCOOP_AI_OUTPUT`      | Default output file path                 | --         |
| `SCOOP_AI_VERIFY`      | Set to `true` to enable install verification | --     |
| `SCOOP_AI_DEBUG`       | Set to `true` to enable debug output     | --         |
| `SCOOP_AI_GITHUB_TOKEN`| GitHub personal access token (fallback) **(sensitive)** | --         |

CLI arguments take precedence over environment variables. Provider-specific API key
env vars (`DEEPSEEK_API_KEY`, `OPENAI_API_KEY`, etc.) are checked as fallbacks when
`SCOOP_AI_API_KEY` is not set.

## Exit Codes

scoop-ai uses the following exit codes (all non-zero codes are stable across versions):

| Code | Constant       | Description                                       |
|------|----------------|---------------------------------------------------|
| 0    | `SUCCESS`      | Operation completed successfully                  |
| 1    | `GENERAL`      | General error (unexpected failure)                |
| 2    | `USAGE`        | Invalid command-line arguments or usage            |
| 3    | `CONFIG`       | Configuration error (e.g., missing `--base-url`)  |
| 64   | `NO_ASSETS`    | No Windows assets found for the given project      |
| 65   | `LLM_ERROR`    | LLM provider error (API failure, timeout)          |
| 66   | `VERIFY_FAILED`| Manifest verification (`scoop install`) failed     |
| 70   | `IO_ERROR`     | File I/O error (write, read, cache)                |

## Cache

Downloaded files are stored in the Scoop cache directory (`~/scoop/cache/`)
using the same naming convention as Scoop. After generating a manifest,
you can run `scoop install <app>` directly -- it will reuse the cached file.

Use `--cache discard` to discard downloaded files after generation.

## Testing

```bash
# Run all tests
./gradlew :cli:test
./gradlew :core:jvmTest

# Force re-run
./gradlew :cli:cleanTest :cli:test --rerun-tasks
./gradlew :core:jvmTest --rerun-tasks
```

## Supported Providers

| Provider           | Default Model       | Notes                                |
|--------------------|--------------------|---------------------------------------|
| `deepseek`         | DeepSeek V4 Flash  | Recommended, best cost/performance   |
| `openai`           | GPT-4o             |                                       |
| `anthropic`        | Claude Opus 4.1    |                                       |
| `ollama`           | Llama 3.2          | Local, no API key needed             |
| `openrouter`       | GPT-4o             |                                       |
| `openai-compatible`| (user-specified)   | Requires `--base-url`                |

See [docs/providers.md](docs/providers.md) for detailed configuration instructions
for each provider, including environment variables, default models, and known
pitfalls.

## License

Private project.
