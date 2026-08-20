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
- Some proxy gateways reject explicit `tool_choice` values. When using such a
  gateway through the `openai-compatible` provider, scoop-ai strips the whole
  `tool_choice` field from every request (see the
  [openai-compatible](#openai-compatible) section). This is a workaround for
  gateways that reject the field: it is only transparent when the gateway's
  default `tool_choice` is `auto`. If the default is not `auto` (e.g. `none`),
  tool calls may stop, and the run relies on Koog's retry/re-prompt path to
  recover.
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
- **Capabilities are uniform for every model name.** scoop-ai no longer
  special-cases DeepSeek model IDs: every openai-compatible model gets the same
  capability set (ToolChoice included, no Thinking capability) and the
  `tool_choice` field is unconditionally stripped from every request body
  (`stripToolChoice`) because Koog's `setToolChoiceRequired()` has no
  capability gating and some gateways reject the field. This is only
  transparent when the gateway's default `tool_choice` is `auto`; if the
  default is not `auto` (e.g. `none`), tool calls may stop and the run relies
  on Koog's retry/re-prompt path. Model-specific
  reasoning retry behavior is handled at runtime by
  `ScoopManifestGenerator.modelWithoutToolChoice` (which strips the ToolChoice
  capability), not by the factory.
- **Recovering malformed tool calls.** Gemini-family models reached through an
  OpenAI-compatible gateway sometimes return `finish_reason:
  "malformed_function_call"` with no `tool_calls` and no `content` (an empty
  assistant turn that Koog would otherwise decode as an "unhandled response" and
  abort on). The response rewriter chain injects a short text retry hint into
  `message.content` (`malformedFunctionCallRetryHint`), so Koog routes the reply
  onto its text/re-prompt edge and the model re-issues the tool call, which
  usually succeeds. Other `finish_reason` values (length/safety/recitation) are
  left untouched. This is a workaround for a transient model output; switch to a
  more robust tool-calling model if it persists.
- All models route to the OpenAI LLM client (OpenAI-shaped responses).

---

## Double Encoding Workaround

### The Problem (historical, now fixed upstream)

The Koog agent framework serialises tool call arguments using
`kotlinx.serialization`. When an argument value is a string that already
contains JSON (e.g. a manifest snippet or URL parameter), the framework
previously double-encoded it: the inner JSON was serialised as a string, then
the outer JSON serialised that string again.

This produced payloads like:

```json
{
  "arguments": "\"{\\\"url\\\":\\\"https://...\\\"}\""
}
```

Most LLM providers handle this correctly. However, some proxy gateways
(particularly those running older or stricter JSON parsers) rejected the
double-encoded payload with a parse error.

### The Fix (resolved in Koog 1.1.1)

Koog 1.1.1 (issue #2096) fixed the root cause of the double encoding in the
OpenAI/DeepSeek clients, so no client-side workaround is needed anymore.

scoop-ai previously shipped a `DoubleEncodingFixHttpClient` interceptor
(`fixDoubleEncoding`) that unwrapped double-encoded arguments/tool-result
content on the outgoing request body. As of the Koog 1.1.1 upgrade this
interceptor -- and its helpers `fixAssistantMessage` / `fixToolMessage` /
`unwrapIfDoubleEncoded` -- has been removed from
`core/.../llm/RequestInterceptors.kt` and from the `LlmProviderFactory`
interceptor chain. A regression test in
`tools/.../llm/ReasoningRetryStubGatewayTest` asserts the real request bodies
captured from a stub gateway are single-encoded.

The fix was applied transparently -- no user configuration was needed, and the
remaining request/response interceptors (`stripToolChoice`,
`reasoningFallbackToContent`, `malformedFunctionCallRetryHint`,
`normalizeMissingCreated`) are unaffected.

### Which providers were affected?

| Provider           | Double encoding workaround |
|--------------------|----------------------------|
| deepseek           | Was built-in; fixed in Koog 1.1.1 |
| openai             | Was built-in; fixed in Koog 1.1.1 |
| anthropic          | Was built-in; fixed in Koog 1.1.1 |
| openai-compatible  | Was via `fixDoubleEncoding` interceptor; removed in Koog 1.1.1 |
| ollama             | No HTTP overhead |
| openrouter         | Was via OpenAI client; fixed in Koog 1.1.1 |

The Koog 1.0.0 DeepSeek and OpenAI built-in clients already handled
double-encoding internally. The `openai-compatible` provider used an
additional `InterceptingHttpClient`, which previously wrapped the
`fixDoubleEncoding` interceptor for extra safety (now removed).

### Debugging double encoding issues

Set `SCOOP_AI_DEBUG_HTTP=true` to log HTTP request bodies (sensitive content
will be masked). This can help identify whether double encoding is causing
LLM errors.
