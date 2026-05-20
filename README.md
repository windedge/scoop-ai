# scoop-ai

AI-powered Scoop manifest generator for Windows packages. Given a GitHub repository URL (or project page), it automatically researches the project, analyzes Windows assets, and generates a standards-compliant [Scoop](https://scoop.sh/) bucket manifest.

## Architecture

scoop-ai uses a **three-phase LLM agent workflow** built on [Koog](https://github.com/JetBrains/koog):

```
Research → Analyze → Generate
  │           │          │
  ▼           ▼          ▼
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
core/   — Business logic, agent strategy, prompts, configuration
tools/  — Tool implementations (HTTP, hashing, archive, manifest I/O)
cli/    — Command-line entry point
```

Dependency direction: `cli → core + tools`, `tools → core`, `core → Koog framework`.

## Build

```bash
# Build all modules
./gradlew build

# Run tests
./gradlew test
```

## Run

```bash
# Basic usage (uses DeepSeek by default)
./gradlew :cli:run --args="https://github.com/sharkdp/fd"

# With specific provider
./gradlew :cli:run --args="https://github.com/jqlang/jq -p openai"

# With output path and verification
./gradlew :cli:run --args="https://github.com/BurntSushi/ripgrep -o bucket/ripgrep.json -V"
```

### CLI Options

```
Usage: scoop-ai <url> [options]

Options:
  -o, --output <path>          Output file path
  -p, --provider <name>        LLM provider: deepseek|openai|anthropic|ollama|openrouter|openai-compatible
  -m, --model <name>           LLM model name (overrides default for provider)
      --base-url <url>         Custom API base URL (required for openai-compatible)
      --api-key <key>          API key
  -V, --verify                 Verify manifest by running scoop install
  -h, --help                   Show this help message
```

### Environment Variables

| Variable             | Description                              | Default    |
|---------------------|------------------------------------------|------------|
| `SCOOP_AI_PROVIDER` | LLM provider                             | `deepseek` |
| `SCOOP_AI_API_KEY`  | API key (fallback for all providers)     | —          |
| `SCOOP_AI_BASE_URL` | Custom API base URL                      | —          |
| `SCOOP_AI_MODEL`    | LLM model name                           | —          |
| `SCOOP_AI_OUTPUT`   | Default output file path                 | —          |
| `SCOOP_AI_VERIFY`   | Set to `true` to enable install verification | —      |
| `SCOOP_AI_DEBUG_HTTP`| Set to `true` to log HTTP request bodies | —         |

Provider-specific API key env vars: `DEEPSEEK_API_KEY`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `OPENROUTER_API_KEY`.

CLI arguments take precedence over environment variables.

## Testing

```bash
# Run all tests
./gradlew test

# Run specific module tests
./gradlew :core:test
./gradlew :tools:test
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

## License

Private project.
