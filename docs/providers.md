# LLM Provider Configuration

scoop-ai supports six LLM providers. The default provider is `deepseek`,
which offers the best cost/performance balance for manifest generation.

## Quick Selection Guide

| If you ...                            | Recommended provider       |
|---------------------------------------|----------------------------|
| Want best cost/performance            | `deepseek` (default)       |
| Already have an OpenAI API key        | `openai`                   |
| Already have an Anthropic API key     | `anthropic`                |
| Want to run locally                   | `ollama`                   |
| Need access to many models            | `openrouter`               |
| Have a self-hosted OpenAI-compatible API | `openai-compatible`    |

## Provider Overview

| Provider            | Env API key                     | Default Model        | Base URL                  | Notes                          |
|---------------------|---------------------------------|----------------------|---------------------------|--------------------------------|
| deepseek            | `DEEPSEEK_API_KEY` **(sensitive)** | DeepSeek V4 Flash   | (built-in)                | Recommended, best value        |
| openai              | `OPENAI_API_KEY` **(sensitive)**   | GPT-4o               | (built-in)                |                                |
| anthropic           | `ANTHROPIC_API_KEY` **(sensitive)**| Claude Opus 4.1     | (built-in)                |                                |
| ollama              | (none needed)                   | Llama 3.2            | `http://localhost:11434`  | Local, no API key required    |
| openrouter          | `OPENROUTER_API_KEY` **(sensitive)** | GPT-4o             | (built-in)                | Multi-model gateway            |
| openai-compatible   | `SCOOP_AI_API_KEY` **(sensitive)**  | (user-specified)    | **required**              | Self-hosted / proxy            |

All providers accept `SCOOP_AI_API_KEY` as a fallback when the
provider-specific env var is not set.

### Provider selection order

1. CLI flag `-P` / `--provider` (highest priority)
2. Environment variable `SCOOP_AI_PROVIDER`
3. Default: `deepseek`

---

## deepseek

The default provider. Uses DeepSeek's official API endpoint.

**Environment variables**:
- `DEEPSEEK_API_KEY` **(sensitive)** -- provider-specific key
- `SCOOP_AI_API_KEY` **(sensitive)** -- fallback

**Default model**: `DeepSeek V4 Flash` (high speed, low cost)

**Configuration examples**:

```bash
# Using provider-specific env var
export DEEPSEEK_API_KEY=sk-your-key-here
java -jar cli.jar https://github.com/sharkdp/fd

# Using fallback env var
export SCOOP_AI_API_KEY=sk-your-key-here
java -jar cli.jar -P deepseek https://github.com/sharkdp/fd

# Override model
java -jar cli.jar -P deepseek -m deepseek-chat https://github.com/sharkdp/fd
```

```cmd
:: Windows CMD
set DEEPSEEK_API_KEY=sk-your-key-here
java -jar cli.jar https://github.com/sharkdp/fd
```

**Known pitfalls**:
- DeepSeek's API may return `tool_choice: required` errors when using certain
  proxy gateways. scoop-ai automatically downgrades `tool_choice` for DeepSeek
  models to avoid this issue.
- See the [Double Encoding Workaround](#double-encoding-workaround) section
  below -- this applies to DeepSeek models accessed both directly and via
  `openai-compatible`.

---

## openai

Uses OpenAI's official API (chat/completions endpoint).

**Environment variables**:
- `OPENAI_API_KEY` **(sensitive)** -- provider-specific key
- `SCOOP_AI_API_KEY` **(sensitive)** -- fallback

**Default model**: `GPT-4o`

**Configuration examples**:

```bash
export OPENAI_API_KEY=sk-proj-your-key
java -jar cli.jar -P openai https://github.com/sharkdp/fd
```

```cmd
set OPENAI_API_KEY=sk-proj-your-key
java -jar cli.jar -P openai https://github.com/sharkdp/fd
```

**Known pitfalls**:
- OpenAI's `gpt-4o` model has strong tool-calling capability but may be more
  expensive than DeepSeek for batch usage.

---

## anthropic

Uses Anthropic's official API (Messages endpoint).

**Environment variables**:
- `ANTHROPIC_API_KEY` **(sensitive)** -- provider-specific key
- `SCOOP_AI_API_KEY` **(sensitive)** -- fallback

**Default model**: `Claude Opus 4.1`

**Configuration examples**:

```bash
export ANTHROPIC_API_KEY=sk-ant-your-key
java -jar cli.jar -P anthropic https://github.com/sharkdp/fd
```

**Known pitfalls**:
- Anthropic models use a different tool-calling format internally. The Koog
  framework handles the translation automatically.
- The Opus 4.1 model is powerful but slower than DeepSeek V4 Flash for this
  use case.

---

## ollama

Runs models locally using Ollama. No API key is required.

**Environment variables**: none (key is automatically set to empty string)

**Default model**: `Llama 3.2` (via `ollama`)

**Configuration examples**:

```bash
# Ensure Ollama is running and the model is pulled
ollama pull llama3.2

# Run scoop-ai with Ollama
java -jar cli.jar -P ollama https://github.com/sharkdp/fd

# Specify a different local model
java -jar cli.jar -P ollama -m llama3.1 https://github.com/sharkdp/fd
```

```cmd
:: First pull the model
ollama pull llama3.2

:: Then generate
java -jar cli.jar -P ollama https://github.com/sharkdp/fd
```

**Known pitfalls**:
- Ollama must be running (`ollama serve`) before scoop-ai starts.
- Local models are significantly slower and less reliable at tool calling than
  cloud providers. You may encounter more agent retries or incomplete results.
- The `deepseek` provider is strongly recommended for production use.

---

## openrouter

Uses OpenRouter's multi-model gateway. Allows access to many models from
different providers through a single API key.

**Environment variables**:
- `OPENROUTER_API_KEY` **(sensitive)** -- provider-specific key
- `SCOOP_AI_API_KEY` **(sensitive)** -- fallback

**Default model**: `GPT-4o` (via OpenRouter routing)

**Configuration examples**:

```bash
export OPENROUTER_API_KEY=sk-or-your-key
java -jar cli.jar -P openrouter https://github.com/sharkdp/fd

# Use a different model via OpenRouter
java -jar cli.jar -P openrouter -m mistralai/mixtral-8x7b-instruct https://github.com/sharkdp/fd
```

**Known pitfalls**:
- OpenRouter model IDs follow the `provider/model` format (e.g.
  `openai/gpt-4o`). The `--model` flag must match OpenRouter's naming.
- Free or low-cost models on OpenRouter may have rate limits or queue delays.

---

## openai-compatible

For self-hosted or proxy API endpoints that implement the OpenAI chat
completions format (e.g. vLLM, TGI, LocalAI, or a company-internal proxy).

**Environment variables**:
- `SCOOP_AI_API_KEY` **(sensitive)** -- required (no provider-specific fallback)
- `SCOOP_AI_BASE_URL` -- **required** (no default)

**Default model**: (user-specified via `--model` or `SCOOP_AI_MODEL`)

**Configuration examples**:

```bash
export SCOOP_AI_API_KEY=sk-your-key
export SCOOP_AI_BASE_URL=http://localhost:8000
java -jar cli.jar -P openai-compatible -m my-model https://github.com/sharkdp/fd

# Or via CLI flags
java -jar cli.jar \
  -P openai-compatible \
  --api-key sk-your-key \
  --base-url http://192.168.1.100:8317 \
  -m opencode/deepseek-v4-flash \
  https://github.com/sharkdp/fd
```

```cmd
set SCOOP_AI_API_KEY=sk-your-key
set SCOOP_AI_BASE_URL=http://192.168.40.130:8317
java -jar cli.jar -P openai-compatible -m opencode/deepseek-v4-flash https://github.com/sharkdp/fd
```

**Known pitfalls**:
- `--base-url` (or `SCOOP_AI_BASE_URL`) is **mandatory**. Without it the
  provider will throw a configuration error (exit code 3).
- The agent appends `v1/chat/completions` to the base URL automatically -- do
  not include the path in the base URL.
- If the model ID contains "deepseek" (case-insensitive), scoop-ai
  automatically:
  1. Uses the DeepSeek LLM client (with the correct response schema).
  2. Injects `LLMCapability.Thinking` for reasoning support.
  3. Applies the `downgradeToolChoice` fix for proxy gateways that reject
     `tool_choice: required`.
- For non-DeepSeek models (e.g. GLM, Qwen), scoop-ai uses the OpenAI LLM
  client to avoid DeepSeek's strict response schema.

---

## Double Encoding Workaround

### The Problem

The Koog agent framework serialises tool call arguments using
`kotlinx.serialization`. When an argument value is a string that already
contains JSON (e.g. a manifest snippet or URL parameter), the framework
double-encodes it: the inner JSON is serialised as a string, then the outer
JSON serialises that string again.

This produces payloads like:

```json
{
  "arguments": "\"{\\\"url\\\":\\\"https://...\\\"}\""
}
```

Most LLM providers handle this correctly. However, some proxy gateways
(particularly those running older or stricter JSON parsers) reject the
double-encoded payload with a parse error.

### The Fix

scoop-ai includes a `DoubleEncodingFixHttpClient` that intercepts HTTP
request bodies before they are sent to the LLM provider. It detects
double-encoded arguments and unwraps them to a single encoding level.

The fix is applied transparently -- no user configuration is needed.

**Code reference**:
- `DoubleEncodingFixHttpClient`:
  `core/src/main/kotlin/com/scoopai/core/llm/DoubleEncodingFixHttpClient.kt`
- Integration in `LlmProviderFactory`:
  `tools/src/jvmMain/kotlin/com/scoopai/tools/llm/LlmProviderFactory.kt`
  (see the `fixDoubleEncoding` interceptor in the `openai-compatible` branch)

### Which providers are affected?

| Provider           | Double encoding fix applied |
|--------------------|-----------------------------|
| deepseek           | Yes (built-in client)       |
| openai             | Yes (built-in client)       |
| anthropic          | Yes (built-in client)       |
| openai-compatible  | Yes (via `fixDoubleEncoding` interceptor) |
| ollama             | No (local, no HTTP overhead)|
| openrouter         | Yes (via OpenAI client)     |

The Koog 1.0.0 DeepSeek and OpenAI built-in clients already handle
double-encoding internally. The `openai-compatible` provider uses an
additional `InterceptingHttpClient` with the `fixDoubleEncoding` interceptor
for extra safety.

### Debugging double encoding issues

Set `SCOOP_AI_DEBUG_HTTP=true` to log HTTP request bodies (sensitive content
will be masked). This can help identify whether double encoding is causing
LLM errors.
