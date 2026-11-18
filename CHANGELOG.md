# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **NOTE**: 0.1.4 artifacts (Maven Local + shadow jar) are published locally
> only; no GitHub Release exists yet. The [0.1.0] section below documents
> completed work that shipped as part of the 0.1.4 artifact. The former
> 0.2.0 / 0.3.0 sections (CLI productization phase numbers, also shipped in
> the 0.1.4 artifact) were folded into the [0.1.4] section, whose "Folded
> phase logs" subsection preserves their entries verbatim.

## [0.1.19] - Unreleased

### Fixed

- Tolerate string persist in normalizeEnvSet so assist runs no longer crash
  with "JsonLiteral is not a JsonArray". A single-string persist is a valid
  Scoop form; treat it as a one-element list.

## [0.1.18] - Unreleased

### Fixed

- Reply-language rules were diluted in the long system prompt, so assistant
  replies and intermediate activities stayed English. Move the rules block to
  the head, add a MANDATORY header and a user-layer anchor, and cover activities
  and tool-call intent lines. Verified against a real gateway with Simplified
  Chinese.
- A null `replyLanguage` left dangling back-references to a rules block that was
  never emitted. Generate the block always, with a neutral fallback rule.

### Changed

- Simplify the language-rule expressions from seven to three.
- Drop the unused retry-log seam and share the sse/lines retry-timeout wrapper.

## [0.1.17] - Unreleased

### Added

- `ScoopAiConfig.replyLanguage`: hosts pass the UI language name as the fallback
  reply language; the shared system prompt picks the user instruction language
  first and falls back to the configured one (manifest data fields stay English)
- `ResearchOutput.user_explanation`: the research phase writes a short
  user-facing explanation for `no_windows_assets` runs, shown in the reply
  language instead of the deterministic English join
- `KoogCompat.decorateHttpClient(onRetryLog = ...)`: hosts plug their own retry
  log sink; each scheduled retry attempt emits one line (attempt, delayMs, cause)

### Changed

- `download_confirm` options carry explicit `value`s (`download` / `skip`)

### Fixed

- SSE and lines responses from the LLM gateway share the post retry budget with
  a whole-stream timeout, so a stalled gateway can no longer hang a run
- `ask_user` download-confirm answers resolve by option index first, then
  legacy keywords, then default to continue

## [0.1.16] - Unreleased

### Changed

- BREAKING: Maven coordinates migrate from `com.scoopai` to `io.github.windedge`
  and the Gradle projects are renamed `scoop-ai-core` / `scoop-ai-tools`
  (directories unchanged); koog dependencies are now exposed as `api`. Kotlin
  package names stay `com.scoopai.*`, so consumers only change dependency
  coordinates.
- MIT `LICENSE` added for the Maven Central publication.
- Windows CI: the three module test suites run on push and pull request;
  testing patterns moved to `docs/testing-guide.md`.

### Removed

- `thinkingCompatibleSingleRunStrategy` and the whole `ThinkingCompatibleStrategy`
  (manual ReAct loop made redundant by the reasoning fallback rewriters plus
  `KoogCompat.decorateModel`)
- `ApiKeyResolver.resolve` / `ProviderConfigResolver.resolve` facade objects and
  the `ScoopManifestConfig` data class: provider resolution now goes through the
  single `resolveProviderConfig` entry point returning `ResolvedProviderConfig`
- Test-only internal surfaces: `isToolCallJsonParseError`,
  `parseResearchStatus`, and the `PhaseTelemetry` duration readers
  (`durationOf` / `durations`)

### Added

- `ResolvedProviderConfig` / `resolveProviderConfig`: the single flag-to-env-to-
  default provider resolution entry point, resolved once and handed to the factory
- `SessionCallbacks`, `AssistHost` and `PhaseTelemetry`: session-scoped adapters
  so the middle layers stop relaying parameters one by one
- `KoogCompat`: the collected Koog workaround seam (HTTP chain decoration, model
  decoration, lenient finish tool) with per-entry trigger/retirement docs
- `ManifestVerifier`: the verify pipeline extracted out of the strategy file,
  shrinking the strategy to the graph

### Fixed

- Autoupdate hash fabrication, fixed in four layers:
  - L1 deterministic validation: guessed checksum companion URLs
    (`<file>.sha256` / `.sha512` / `.md5`) that are absent from the actual
    release assets are flagged as repair issues by a deterministic verifier;
    templates with variables that cannot be resolved safely are skipped
  - L2 checksums cross-check: the research phase's checksums file note is
    validated against the real release asset names and the must-add hash
    instruction is dropped on mismatch; checksums-bearing analysis notes are
    filtered before they reach the generate prompt
  - L3 digest passthrough: the GitHub release asset digest (SHA-256) is
    surfaced as the manifest `hash` value so the model no longer has to
    invent checksum file names
  - L4 prompt counter-example: generate/repair prompts state that GitHub
    releases rarely ship per-file checksum siblings and to omit the hash
    block instead of guessing a URL

## [0.1.15] - 2026-10-23

### Added

- `ProviderEndpoints`: the single source of truth for per-provider base URLs,
  paths, auth headers and default models; deepseek, openai, anthropic, ollama
  and openrouter now honor base URL overrides (openai-compatible flow unchanged)

### Changed

- Anthropic and ollama clients are wrapped in the shared intercepting HTTP
  client so they get the same retry window as the other providers
- The unsupported-provider error message is unified across the API key resolver
  and the provider factory

### Fixed

- Assist saves that keep the referenced app's base name no longer trigger the
  app name conflict notice; renames inside a session and plain generate runs
  still report the conflict

## [0.1.14] - 2026-10-20

### Added

- Assist sessions expose the nine read-only GitHub and web tools so chat
  answers verify facts instead of guessing; prompts require citing sources
  and forbid the heavy download tools

### Changed

- Assist prompt budget numbers aligned with the code constants; wrong retry
  denominator dropped

### Fixed

- Shared HTTP retry window widened (five retries, thirty second backoff cap)
  so transient gateway errors survive an outage; connect and request timeouts
  added to the cache manager and the redirect resolver so a black-holed host
  cannot hang a run

## [0.1.13] - 2026-10-06

### Changed

- Tool activity events carry the model's self-declared intent sentence captured
  before each tool call; research and analyze prompts ask for one short intent
  sentence before tool use
- Shared system prompt style rule bans em and en dashes in all model output

## [0.1.10] - 2026-09-24

### Added

- `read_manifest_reference` host tool: resolves `@bucket/app` references to
  local manifest content with metadata, returning structured errors instead of
  throwing; registered for assist sessions via the host-provided handler and
  documented in the assist and repair prompts

## [0.1.9] - 2026-09-21

### Added

- Hash auto-fix: invalid SHA-256 hashes found during verify are corrected from
  analyze/research data without consuming repair rounds
- `install_app` accepts a nullable bucket for bucketless direct installs, with
  prompt guidance

### Changed

- Repair retry limit raised from 3 to 5

### Fixed

- Text-only assist answers are recovered as explanations instead of the
  failure fallback

## [0.1.8] - 2026-09-07

### Added

- `verify_install` tool: assist-phase install tests backed by the refactored
  `ProcessScoopVerifier` (app name whitelist, per-instance budget); injected
  into the semantic review agent and the assist subgraph
- Host-callback `install_app` and `save_to_bucket` tools for assist sessions,
  registered only when the host provides the handlers so CLI builds stay
  tool-free
- Self-diagnosing install tests: the verifier captures why the scoop probe
  failed and the reason flows into the assist prompt's no-install-test branch
  so the model can report the root cause
- Runtime-failure guidance: user reports of an installed app failing are
  treated as manifest fix requests (fix, save to the same bucket with
  overwrite, reinstall), bounded to two attempts

### Changed

- BREAKING: install verification is always available; `ScoopAiConfig.verify`
  and the CLI `--verify` flag are removed. The verifier is created
  opportunistically: when scoop cannot be resolved the tools stay absent and
  the assist prompt reports it honestly

### Fixed

- Windows: scoop is resolved via the `scoop.cmd` shim (ProcessBuilder cannot
  exec `.cmd` files directly) so `verify_install` is no longer silently
  unavailable; the failure reason is logged instead of swallowed
- Tool events carry raw, untruncated results and unwrap Koog `JSONLiteral`
  strings so hosts can parse `verify_install` output
- The generate summary survives repair rounds instead of being overwritten by
  the repair explanation
- Redundant `verify_install` re-runs are forbidden when failure details are
  already embedded in the user message
- Saves without an explicit bucket follow the needsBucket flow (single-choice
  `__ask_user__` question); a review retest note makes the review agent re-run
  `verify_install` after a fix instead of trusting the stale failed result

## [0.1.7] - 2026-09-02

### Changed

- BREAKING: refine is renamed to assist/repair - `SessionRequest.Refine` becomes
  `Assist`, `RefineContext` becomes `AssistContext`, `generator.refine()`
  becomes `assist()`; user follow-ups emit the "assist" phase, the internal
  loop emits "repair"
- Prompts split into `buildAssistPrompt` / `buildRepairPrompt` with a question
  escape hatch; `FinalizePayload.answered` and `ManifestResult.answered` guard
  the answer path (the manifest must stay unchanged)

## [0.1.6] - 2026-09-01

### Added

- Real-time `ToolActivity` listener for agent execution
- AI explanation carried through finalize: dual-parameter `FinalizePayload`
  protocol (result + optional explanation) with lenient decoding and legacy
  compatibility; `ManifestResult.explanation`; verify phase events with review
  instrumentation merged into verify stats
- Multi-round refine history with normalized output; session evidence feeds
  refine; `SafeAskUser` tool instance decoupled
- Squirrel installer pattern and reference manifest for Electron apps

### Changed

- Prompts prioritize the official GitHub About description and enforce Scoop
  description standards
- HTTP exponential backoff retry with jitter

### Fixed

- Review and refine taught about NSIS `#/dl.7z` extraction (bin/shortcuts
  targets exist after 7-Zip extraction; GUI-only apps may omit bin)
- Dual bin conflicts resolved; prompts enforce exclusive bin placement

## [0.1.5] - 2026-08-27

### Added

- `ScoopManifestGenerator.phaseOutputListener`: optional in-memory callback invoked
  once per completed phase (research / analyze / generate / refine) with the raw,
  unmasked phase output JSON. Merged with the existing artifacts disk writer so
  both persist simultaneously. Intended for in-memory checkpoints only; content
  is not masked, so it must not be persisted in sensitive scenarios.
- `DefaultScoopAiFactory.createGenerator` `onPhaseOutput` parameter transparently
  forwarded to `ScoopManifestGenerator.phaseOutputListener`.
- `ResumeData.fromPhaseOutputs` factory: builds a `ResumeData` from raw in-memory
  phase output JSON strings for in-memory checkpoint resume. ANALYZE needs
  research; GENERATE needs research + analyze; returns null on missing or corrupt
  input; ignores irrelevant (e.g. refine/generate) outputs.
- Tests: `ResumeData.fromPhaseOutputs` round-trip (ANALYZE/GENERATE, missing /
  corrupt JSON, ignored extras), `phaseOutputListener` mock-executor flow, and
  `DefaultScoopAiFactory` `onPhaseOutput` construction.

## [0.1.4] - 2026-08-18

> **Phase fold**: the former 0.2.0 and 0.3.0 sections documented internal
> feature phase numbers (Phase 2-3 CLI productization) of the same code line
> that shipped as the 0.1.4 artifact. Their entries are preserved verbatim in
> the "Folded phase logs" subsection at the end of this section.

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

### Folded phase logs (former 0.2.0 / 0.3.0 sections)

The entries below were previously separate 0.2.0 and 0.3.0 sections. They are
preserved verbatim; the former 0.2.0 content (clikt subcommand architecture
summary) is fully covered by these entries.

#### Added

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

#### Changed

- CLI refactored to clikt 4.4.0 with subcommand architecture (`ScoopAi.kt`)
- `scoop-ai generate <url>` is now the canonical form; `scoop-ai <url>` still works via automatic routing
- `--version` and `scoop-ai version` subcommand both print version info via clikt
- Help output is now clikt-style (replaces hand-written `HELP_TEXT` and custom help printers)
- `Main.kt` reduced to 7 lines (delegates to `CliRunner`)
- Retained `CliRunner.kt` as clikt subcommand entry point (main entry delegates to CliRunner)
- `ManifestResult` now has `stages` field (backward compatible, defaults to empty)
- `TokenUsage` and `StageInfo` are `@Serializable`
- README updated: WARNING block for sensitive env vars, links to docs, Installation section

#### Fixed

- scoop-installed doctor check: Windows ProcessBuilder could not find `scoop.cmd` shim (now uses `cmd /c` + shim file fallback)
- scoop-installed version extraction: outputs clean version (e.g. `0.5.3`) instead of `Current Scoop version:`

#### Notes

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
  - 0.3.0 does NOT include `--from <stage>` (superseded: the 0.1.4 artifact itself
    ships `--from`, see the section above).

#### Known Limitations

- No persistent configuration file (`~/.scoopai/config.toml` planned for v0.4.0)
- No keyring integration for API key storage (planned for v0.4.0)
- No `--from <stage>` checkpoint resume (planned for v0.4.0+ per T-3.05b spike)
- No internationalization (English only, no Chinese README)

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
[0.1.16]: https://github.com/placeholder/scoop-ai/releases/tag/v0.1.16
[0.1.15]: https://github.com/placeholder/scoop-ai/releases/tag/v0.1.15
[0.1.14]: https://github.com/placeholder/scoop-ai/releases/tag/v0.1.14
[0.1.13]: https://github.com/placeholder/scoop-ai/releases/tag/v0.1.13
[0.1.10]: https://github.com/placeholder/scoop-ai/releases/tag/v0.1.10
[0.1.9]: https://github.com/placeholder/scoop-ai/releases/tag/v0.1.9
[0.1.8]: https://github.com/placeholder/scoop-ai/releases/tag/v0.1.8
[0.1.7]: https://github.com/placeholder/scoop-ai/releases/tag/v0.1.7
[0.1.6]: https://github.com/placeholder/scoop-ai/releases/tag/v0.1.6
[0.1.5]: https://github.com/placeholder/scoop-ai/releases/tag/v0.1.5
[0.1.4]: https://github.com/placeholder/scoop-ai/releases/tag/v0.1.4
[0.1.0]: https://github.com/placeholder/scoop-ai/releases/tag/v0.1.0
