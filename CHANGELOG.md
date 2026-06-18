# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `scoop-ai doctor` subcommand: 7 health checks (JDK version, OS, Scoop install, API key, cache dir, write permission, version)
- `scoop-ai config show` subcommand: display runtime configuration (API keys masked)
- `scoop-ai completion bash|zsh|fish|powershell` subcommand: shell completion scripts
- `scoop-ai version` subcommand: print version info
- `--debug` flag: print stack traces on errors (credentials still masked)
- `--no-progress` flag: disable TTY progress overlay
- `CompositionRoot.kt`: lazy dependency injection root
- `EnvArgs.kt`: pure CLI args parser
- Comprehensive CLI test suite: GenerateCommandTest (42), DoctorCommandTest (13), ConfigShowCommandTest (13), CompletionCommandTest (19), VersionCommandTest (9), VersionOptionIntegrationTest (2)

### Changed

- CLI refactored to clikt 4.4.0 with subcommand architecture (`ScoopAi.kt`)
- `scoop-ai generate <url>` is now the canonical form; `scoop-ai <url>` still works via automatic routing
- `--version` and `scoop-ai version` subcommand both print version info via clikt
- Help output is now clikt-style (replaces hand-written `HELP_TEXT` and custom help printers)
- `Main.kt` reduced to 7 lines (delegates to `CliRunner`)
- Removed dead `CliRunner.kt` (pre-clikt implementation)

### Known Limitations

- No persistent configuration file (`~/.scoopai/config.toml` planned for v0.4.0)
- No keyring integration for API key storage (planned for v0.4.0)
- No structured JSON output (`--output-format json` planned for v0.3.0)
- No run artifacts / checkpoint recovery (spike planned for v0.3.0)
- No self-bootstrapping Scoop manifest (`bucket/scoop-ai.json` planned for v0.3.0)
- No man page (planned for v0.3.0)

## [0.1.0] - 2026-06-16

### Added

- Three-phase LLM agent (research / analyze / generate) for generating Scoop bucket manifests from GitHub repository URLs.
- Research phase: project info gathering via GitHub API, web scraping, Windows asset discovery, and persist target analysis.
- Analyze phase: SHA-256 hash computation, ZIP archive inspection (content listing, NSIS detection, executables discovery).
- Generate phase: Scoop manifest construction, JSON schema validation, and optional `scoop install` verification.
- Refine phase: review-agent based manifest quality check and iterative improvement.
- LLM provider abstraction layer supporting six providers: DeepSeek, OpenAI, Anthropic, Ollama, OpenRouter, and OpenAI-compatible endpoints.
- Provider-specific default models with fallback: DeepSeek V4 Flash, GPT-4o, Claude Opus 4.1, Llama 3.2, etc.
- CLI options: `--output`, `--provider`, `--model`, `--base-url`, `--api-key`, `--github-token`, `--prompt`, `--app-name`, `--persist`, `--no-cache`, `--non-interactive`, `--debug`, `--verify`, `--version`.
- Environment variable configuration for all CLI flags (e.g., `SCOOP_AI_PROVIDER`, `SCOOP_AI_API_KEY`, `SCOOP_AI_MODEL`).
- Scoop download cache reuse: downloaded files cached in `~/scoop/cache/` and reused by `scoop install`.
- Persist field policy: `auto` (infer from code analysis), `force` (always include), `none` (no persist field).
- Manifest output to file, with informative feedback (suggested app name, file path, token usage, run duration).
- Exit code system: `SUCCESS(0)`, `GENERAL(1)`, `USAGE(2)`, `CONFIG(3)`, `NO_ASSETS(64)`, `LLM_ERROR(65)`, `VERIFY_FAILED(66)`, `IO_ERROR(70)`.
- Structured error handling via sealed `CliError` class mapping one-to-one to exit codes.
- Credential masking (`SensitiveMasker`): API keys, GitHub tokens, and `Authorization` headers redacted in all console output.
- Phase progress logging (`[phase]` lines on stderr with duration and token counts).
- Console progress overlay (`\r[phase] <name>` with TTY detection and `disable()` support).
- Logback configuration: Koog framework noise suppressed (`WARN`/`ERROR`), `com.scoopai` at `INFO`.
- `--version` flag with build-time version injection and git commit hash resolution.
- `--debug` flag for stack trace display (with credential masking preserved).
- `--non-interactive` mode for batch/CI usage (skips `__ask_user__` prompts).
- `--github-token` flag and `GITHUB_TOKEN` environment variable fallback to avoid API rate limits.
- Automatic app name inference from GitHub URLs (`owner/repo`).
- Gradle multi-module project structure (`cli`, `core`, `tools`) with shadow fat jar packaging.
- Centralized version management via `gradle.properties`.
- Comprehensive test suite: pure unit tests, mock unit tests, mock integration tests (Koog `agents-test`), and end-to-end verification.
- Nested archive inspection for tools like `ripgrep` (`.tar.gz` extraction within ZIP).
- Tool call budget tracking and reset for fresh agent runs.
- HTTP request body fix for deepseek/openai-compatible double-encoding issues (`DoubleEncodingFixHttpClient`).

### Known Limitations

- No persistent configuration file (`~/.scoopai/config.toml` planned for v0.4.0).
- No keyring integration for API key storage (planned for v0.4.0).
- No shell completion scripts (planned for v0.2.0 via clikt).
- No structured JSON output (`--output-format json` planned for v0.3.0).
- No run artifacts / checkpoint recovery (spike planned for v0.3.0).
- No `doctor` subcommand for health checks (planned for v0.2.0).
- No `config show` subcommand (planned for v0.2.0).
- No self-bootstrapping Scoop manifest (`bucket/scoop-ai.json` planned for v0.3.0).
- No man page (planned for v0.3.0).
- No internationalization (English only, no Chinese README).
