# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **NOTE**: 0.1.4 artifacts (Maven Local + shadow jar) are published locally
> only; no GitHub Release exists yet. The [0.1.0], [0.2.0], and [0.3.0]
> sections below document completed work that shipped as part of the 0.1.4
> artifact.

## [0.3.0] - 2026-06-18

### Added

- `scoop-ai doctor` subcommand: 7 health checks (JDK version, OS, Scoop install, API key, cache dir, write permission, version)
- `scoop-ai config show` subcommand: display runtime configuration (API keys masked)
- `scoop-ai completion bash|zsh|fish|powershell` subcommand: shell completion scripts
- `scoop-ai version` subcommand: print version info
- `--debug` flag: print stack traces on errors (credentials still masked)
- `--no-progress` flag: disable TTY progress overlay
- `CompositionRoot.kt`: lazy dependency injection root
- `EnvArgs.kt`: pure CLI args parser
- JSON output mode (`--output-format json`): structured output with ManifestResult (manifest, suggestedAppName, noAssets, tokenUsage, durationMs, stages)
- `StageInfo` data class: per-phase execution statistics (phaseName, durationMs, inputTokens, outputTokens, toolCallCount)
- `GenerationObserver`: records per-phase telemetry (enterPhase/exitPhase/recordLlmCall/recordToolCall)
- `RunArtifacts`: writes phase JSON to `.scoopai/runs/<runId>/` for debugging (`--artifacts auto|always|never`, `--run-id override`)
- `SensitiveArtifactWriter`: wraps RunArtifacts with SensitiveMasker for safe debug output
- `--cache keep|discard` flag (`--no-cache` deprecated, prints warning)
- Self-bootstrapping Scoop manifest (`bucket/scoop-ai.json`)
- `docs/troubleshooting.md`: 16 FAQ covering exit codes and common issues
- `docs/providers.md`: 6 providers with double-encoding workaround section
- `docs/man/scoop-ai.1`: troff man page (installManPage Gradle task)
- Comprehensive CLI test suite: GenerateCommandTest (42), DoctorCommandTest (13), ConfigShowCommandTest (13), CompletionCommandTest (19), VersionCommandTest (9), VersionOptionIntegrationTest (2)
- `StageInfoTest`, `GenerationObserverTest`, `RunArtifactsTest`, `SensitiveArtifactWriterTest`

### Changed

- CLI refactored to clikt 4.4.0 with subcommand architecture (`ScoopAi.kt`)
- `scoop-ai generate <url>` is now the canonical form; `scoop-ai <url>` still works via automatic routing
- `--version` and `scoop-ai version` subcommand both print version info via clikt
- Help output is now clikt-style (replaces hand-written `HELP_TEXT` and custom help printers)
- `Main.kt` reduced to 7 lines (delegates to `CliRunner`)
- Retained `CliRunner.kt` as clikt subcommand entry point (main entry delegates to CliRunner)
- `ManifestResult` now has `stages` field (backward compatible, defaults to empty)
- `TokenUsage` and `StageInfo` are `@Serializable`
- README updated: WARNING block for sensitive env vars, links to docs, Installation section

### Fixed

- scoop-installed doctor check: Windows ProcessBuilder could not find `scoop.cmd` shim (now uses `cmd /c` + shim file fallback)
- scoop-installed version extraction: outputs clean version (e.g. `0.5.3`) instead of `Current Scoop version:`

### Notes

- T-3.05b checkpoint spike concluded: RESUME_DEFERRED (deferred to v0.4.0+)
  - Koog exposes a first-class checkpoint resume API (Persistence.runFromCheckpoint +
    AdditionalInputs.Storage + strategy restoreStateIfNeeded), so this is not a framework blocker.
  - scoopManifestStrategy is not resumable yet: phase data (researchData/analyzeData/
    suggestedAppName) lives in local var captures set as side effects inside node bodies,
    which a checkpoint restore skips, causing NPE in prepareManifest/generate.
  - Shipping --from needs a capture->storage refactor (~40 lines) plus wiring + proof test
    (~200 lines), above the spike's RESUME_SUPPORTED threshold, on the hottest code path.
  - v0.4.0 plan: refactor captures to storage keys, add resume-after-subgraph proof test,
    add CheckpointResume.kt, wire --from <analyze|generate>.
  - v0.3.0 does NOT include `--from <stage>` (deferred to v0.4.0+).

### Known Limitations

- No persistent configuration file (`~/.scoopai/config.toml` planned for v0.4.0)
- No keyring integration for API key storage (planned for v0.4.0)
- No `--from <stage>` checkpoint resume (planned for v0.4.0+ per T-3.05b spike)
- No internationalization (English only, no Chinese README)

## [0.1.4] - 2026-08-18

### Added

- `--from <analyze|generate>` flag for checkpoint resume (skips completed phases)
- `CheckpointResume.kt`: loads phase outputs from `.scoopai/runs/<runId>/` and constructs
  Koog checkpoints for resuming generation from a specific stage
- `ResumeData.toAgentCheckpointData()`: converts phase outputs to `AgentCheckpointData`
  for `Persistence.runFromCheckpoint`
- `ScoopManifestGenerator.generate()` now accepts optional `resumeData` parameter
  for checkpoint resume
- `CheckpointResumeTest.kt`: 9 tests covering ANALYZE/GENERATE resume, missing
  artifacts, corrupt JSON, and checkpoint construction
- `--from` CLI tests: 7 tests for parsing, validation, and combined flags
- `LenientStringFinishTool`: drop-in replacement for Koog's `FinishTool<String>`
  used by the generate/refine subgraphs; accepts the manifest result as JSON
  string, JSON object, array, literal, or null and always decodes to a string.
  Fixes the `IndexOutOfBoundsException(-1)` when the model passes the manifest
  as a JSON object instead of a stringified manifest (tool name stays
  `finalize_task_result`, prompts and strategy unchanged)
- `ScoopAiGenerationException`: typed generation failure, thrown when Koog's
  `subgraphWithTask` exhausts re-prompts without a single tool call (model
  lacks function calling); message carries a multi-line cause analysis and fix
  hint; CLI classifies it as LLM_ERROR (exit 65) and passes the message through
  untruncated
- `ManifestResult.exhausted` / `issues`: refine-loop exhaustion now surfaces
  the unresolved validation/review issues as a structured list; CLI adds a
  manifest rejection guard that refuses to report blank, exhausted, or
  invalid-JSON output as success ("Manifest NOT ready")
- Observability: `GenerationObserver` records the per-phase LLM call count
  (`lastCompletedLlmCallCount()`), and `PhaseProgressLogger.completed()`
  appends ", N LLM calls" (useful for proving Koog retries occurred)
- Retry / stub-gateway tests: `ScoopManifestGeneratorRetryMockTest`,
  `ScoopManifestGeneratorToolCallExhaustionMockTest`, and
  `ReasoningRetryStubGatewayTest` (stub gateway full chain, incl. the
  "Koog 1.1.1 no longer double-encodes tool call arguments" regression)

### Changed

- Koog upgraded 1.0.0 -> 1.1.1 (`koog-agents`); `prompt-executor-deepseek-client`
  upgraded 1.0.0-beta-preview7 -> 1.1.1-beta
- Removed the `fixDoubleEncoding` request interceptor and its helpers
  (`fixAssistantMessage` / `fixToolMessage` / `unwrapIfDoubleEncoded`): Koog
  1.1.1 (#2096) fixed the double-encoding of tool call arguments / tool result
  content at the source, so the client-side rewrite is no longer needed
- Response rewriting chain: `reasoningFallbackToContent` runs first (maps
  reasoner responses with blank `content`, no `tool_calls`, and reasoning held
  in `reasoning_content` / `reasoning` / `reasoningContent` into `content`),
  then `normalizeMissingCreated` fills in the omitted `created` field
- `ScoopManifestGenerator.generate()`: when `resumeData` is provided, uses
  `Persistence.runFromCheckpoint` instead of `agent.run()` to skip completed phases
- `openai-compatible` provider: removed all DeepSeek model-name special-casing.
  Capabilities are now uniform for every model name (ToolChoice included, no
  Thinking capability); `defaultModel()` uses the generic model-name branch
  (no borrowed `DeepSeekV4Flash` contextLength/maxOutputTokens);
  `isDeepSeekModelName()` was deleted. The request-side `downgradeToolChoice`
  interceptor (DeepSeek-only) was replaced by unconditional `stripToolChoice`,
  which removes the whole `tool_choice` field from every openai-compatible
  request body because Koog's `setToolChoiceRequired()` has no capability
  gating and some gateways reject the field. Reasoning retry behavior is still
  handled at runtime by `ScoopManifestGenerator.modelWithoutToolChoice`.

### Fixed

- Blank / exhausted / invalid-JSON manifests are no longer reported as success:
  the CLI prints "Manifest NOT ready" with the rejection detail ("no manifest
  generated", "refine loop exhausted with N unresolved issue(s): ...", or
  "generated manifest is not valid JSON") and exits with the GENERAL code
- Pure-reasoning responses (blank `content`, no `tool_calls`, thinking held in
  `reasoning_content`) no longer abort `subgraphWithTask` with "must always
  call tools": `reasoningFallbackToContent` maps the reasoning text into
  `message.content` so Koog decodes it as text and the strategy graph recovers
- OpenAI-compatible gateways that omit the required `created` field no longer
  fail decoding with `MissingFieldException`: `normalizeMissingCreated` adds a
  locally observed timestamp before Koog parses the response
- Koog's `FinishTool<String>` no longer throws `IndexOutOfBoundsException(-1)`
  when the model returns the manifest as a JSON object: generate/refine use
  `LenientStringFinishTool` which accepts object / string / literal results
- Tool-call exhaustion in `subgraphWithTask` now surfaces as
  `ScoopAiGenerationException` (exit 65 LLM_ERROR) with an actionable hint
  instead of a bare `IllegalStateException`; the exception message is passed
  through the CLI without first-line truncation so the cause analysis / fix
  hint reaches the user

### Notes

- Resolves T-3.05b checkpoint resume (previously RESUME_DEFERRED)
- `--from` requires `--run-id`; provides a clear error message when missing
- Falls back to full generation if artifacts are missing or corrupt
- End-to-end resume verification with real LLM deferred to batch 4

## [0.2.0] - 2026-06-17

Unreleased alongside 0.3.0; documents the clikt subcommand architecture introduced in Phase 2.

### Added

- clikt 4.4.0 subcommand architecture (generate/doctor/config/completion/version)
- CompositionRoot.kt + EnvArgs.kt
- DoctorCommand with 7 HealthCheck
- ConfigShowCommand, CompletionCommand, VersionCommand
- 5 subcommand test suites

### Changed

- Main.kt trimmed from 300 to 10 lines (delegates to CliRunner)
- Help output is clikt-style

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

<!-- TODO: replace the placeholder repo URL below with the real repository once the first GitHub Release is cut -->
[0.1.4]: https://github.com/placeholder/scoop-ai/releases/tag/v0.1.4
[0.3.0]: https://github.com/placeholder/scoop-ai/releases/tag/v0.3.0
[0.2.0]: https://github.com/placeholder/scoop-ai/releases/tag/v0.2.0
[0.1.0]: https://github.com/placeholder/scoop-ai/releases/tag/v0.1.0
