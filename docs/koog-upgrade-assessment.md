# Koog 升级评估: 1.1.1 -> 1.3.0 (HEAD)

只读分析, 未改任何代码。

- 评估对象: scoop-ai 当前 `ai.koog:koog-agents:1.1.1` + `ai.koog:prompt-executor-deepseek-client:1.1.1-beta`
- Koog 仓: `C:/src/kotlin/koog`, 分支 `develop`, HEAD = `22e342e03` (2026-10-13), `gradle.properties` version = 1.3.0
- 参照发布 tag: `1.1.1` (2026-07-20) / `1.2.0` (2026-08-28) / `1.3.0` (2026-09-23)
- 重要前提: `1.1.1` / `1.2.0` / `1.3.0` 三个 tag **都不是** develop HEAD 的祖先 (`git merge-base --is-ancestor` 全为 NO), develop 被重写过 (squash 式提交)。因此本次比对一律用 **tree diff** (`git diff 1.1.1..HEAD -- <path>`), 结论按目录内容而非提交拓扑。

---

## 1. 版本差表 (1.1.1 -> HEAD)

### 1.1 发布条目全貌

| 版本 | 发布日期 | 与 scoop-ai 相关的内容 |
|---|---|---|
| 1.1.1 | 2026-07-20 | 双编码修复 (#2096) 已含 -> `fixDoubleEncoding` 已删; `freshHistory` 参数; sealed 输出支持 (#2013); Anthropic enum 序列化修复; 流式 tool-call 分片修复 |
| 1.2.0 | 2026-08-28 | 新 `skills` 模块; 新 `koog-bedrock-agentcore-runtime` 模块; OpenAI Responses `instructions` 容错 (#2220); **OpenAI 流式 delta 支持 `reasoning_content`** (#2190) |
| 1.3.0 | 2026-09-23 | 全 provider 新模型定义 (#2230); Google `cachedContentTokenCount` (#2249); Langfuse 空 reasoning part (#2248) |
| HEAD (未发版) | 2026-10-13 | 仅 Langfuse span 修复 (#2298) + koog-ktor SSE 多行修复 (#2289) + Bedrock AgentCore runtime 微调 |

### 1.2 tree diff 总览

```
git diff --stat 1.1.1..HEAD  ->  85 files changed, 5865 insertions(+), 338 deletions(-)
```

按模块归类:

| 模块 | 1.1.1 -> HEAD 变化 | 是否影响 scoop-ai |
|---|---|---|
| `agents/agents-core` | **零变化** | 否 |
| `agents/agents-test` | **零变化** | 否 |
| `http-client/*` | **零变化** | 否 |
| `serialization/*`, `utils/*` | **零变化** | 否 |
| `prompt/prompt-llm` (含 `LLMCapability` / `LLMProvider`) | **零变化** | 否 |
| `prompt/prompt-model` | **零变化** | 否 |
| `prompt/prompt-executor/prompt-executor-model` (含 `MultiLLMPromptExecutor`) | **零变化** | 否 |
| `prompt/prompt-executor/prompt-executor-clients/src` (含 `RetryingLLMClient` / `RetryConfig`) | **零变化** | 否 |
| `prompt/.../prompt-executor-openai-client-base` | 仅 `OpenAIStreamDelta` 加 `reasoningContent` 字段 (+`OpenAIDataModels` 注释) | 轻微 (流式 reasoning 上报) |
| `prompt/.../prompt-executor-openai-client` | `OpenAILLMClient` 流式分支加 `emitReasoningDelta` | 轻微 |
| `prompt/.../prompt-executor-deepseek-client` | `DeepSeekLLMClient` 流式分支加 `emitReasoningDelta` (+新模型) | 轻微 |
| `prompt/.../prompt-executor-google-client` | `cachedContentTokenCount` 写入 metadata (+新模型) | 否 (未使用 google) |
| `prompt/.../prompt-executor-{anthropic,ollama,openrouter}-client` | **仅新模型定义**, 客户端逻辑零变化 | 否 |
| `agents/agents-features/*` | 仅 Langfuse span adapter | 否 |
| 新增 | `skills/` 模块, `koog-bedrock-agentcore-runtime/` 模块, `examples/koog-agentcore-runtime/` | 否 |

**结论: 1.1.1 -> 1.3.0/HEAD 之间, scoop-ai 实际依赖的全部 Koog 模块 (agents-core / agents-test / http-client / prompt-llm / prompt-model / prompt-executor-model / prompt-executor-clients 公共层) 字节级零差异。**

---

## 2. 破坏性变更表 (逐项核对 scoop-ai 用到的 API)

| scoop-ai 用到的 Koog API | 位置 | 1.3.0/HEAD 状态 | 判定 |
|---|---|---|---|
| `strategy {}` / `subgraph {}` / `nodeLLMRequest` / `onTextMessage` / `asUserMessage` DSL | `core/.../ScoopManifestStrategy.kt`, `ThinkingCompatibleStrategy.kt` | agents-core 目录 tree diff 为空, API dump `agents-core.klib.api` 未变 | **不变** |
| `subgraphWithTask` / `AIAgentSubgraphExt` | `core/.../ScoopManifestGenerator.kt` | 同上 | **不变** |
| `AIAgent` / `AIAgentConfig` / `AIAgent(...) { withTesting() }` | `core/.../ScoopManifestGenerator.kt` | 同上 | **不变** |
| `FinishTool<Output>` / `SubgraphWithTaskUtils` | `core/.../LenientStringFinishTool.kt` | `AIAgentSubgraphExt.kt` 无 diff, `FinishTool.decodeOutput` 仍是严格 `FinishResult<Output>` 解码 | **不变 (语义未变, 容错仍未加)** |
| `LLMCapability` (含 `OpenAIEndpoint.Completions` / `Thinking` / `ToolChoice`) | `tools/.../LlmProviderFactory.kt` | `prompt/prompt-llm` tree diff 为空 | **不变** |
| `LLMProvider` (OpenAI / Anthropic / Ollama / OpenRouter / DeepSeek) | 同上 | 同上 | **不变** |
| `MultiLLMPromptExecutor` | `tools/.../LlmProviderFactory.kt` | `prompt-executor-model` tree diff 为空 | **不变** |
| `OpenAILLMClient` / `OpenAIClientSettings` / `AbstractOpenAILLMClient.createConfiguredHttpClient` | `tools/.../LlmProviderFactory.kt` | `AbstractOpenAILLMClient.kt` 无 diff, 签名 (`apiKey, settings, httpClientFactory, clientName`) 不变 | **不变** |
| `DeepSeekLLMClient` / `DeepSeekClientSettings` | 同上 | 仅流式分支 + 新模型, 构造签名不变 | **不变** |
| `AnthropicLLMClient` / `AnthropicClientSettings` | 同上 | 客户端文件无 diff | **不变** |
| `OllamaClient` | 同上 | 客户端文件无 diff | **不变** |
| `OpenRouterLLMClient` / `OpenRouterClientSettings` | 同上 | 客户端文件无 diff | **不变** |
| `JavaKoogHttpClient.Factory()` | `tools/.../LlmProviderFactory.kt` | `http-client-java` tree diff 为空 | **不变** |
| `KoogHttpClient` 接口 / `KoogHttpClient.Factory` (scoop-ai 自实现 `InterceptingHttpClient`) | `core/.../InterceptingHttpClient.kt` | `http-client-core` tree diff 为空 | **不变** |
| `getMockExecutor` / `withTesting` / `SimpleTool` | `core/src/commonTest/**` | `agents-test` tree diff 为空 | **不变** |
| `Tool` / `ToolDescriptor` / `ToolParameterDescriptor` / `JSONSerializer` / `JSONElement` / `typeToken` | 多处 | agents-core + serialization tree diff 为空 | **不变** |
| `RetryingLLMClient` / `RetryConfig` / `toRetryingClient()` | 尚未使用 | `prompt-executor-clients/src` tree diff 为空, 1.1.1 已具备 | **新增可用 (1.1.1 就有)** |

**破坏性变更总数: 0。** 唯一的行为变化是三个 OpenAI 系 client 的 **流式** 路径开始把 `reasoning_content` 转成 `MessagePart.Reasoning` (影响流式, 不影响非流式)。

---

## 3. provider 覆盖表 (HEAD)

### 3.1 HEAD 可用的 LLM client 模块与发布版本

| 模块 | artifact | 1.3.0 发布版本 | 支持自定义 baseUrl | 自带重试 |
|---|---|---|---|---|
| prompt-executor-openai-client | `ai.koog:prompt-executor-openai-client` | `1.3.0` (stable) | 是 (`OpenAIClientSettings.baseUrl`) | 否 |
| prompt-executor-anthropic-client | `ai.koog:prompt-executor-anthropic-client` | `1.3.0` (stable) | 是 (`AnthropicClientSettings.baseUrl`) | 否 |
| prompt-executor-ollama-client | `ai.koog:prompt-executor-ollama-client` | `1.3.0` (stable) | 是 (`OllamaClient(baseUrl=)`) | 否 |
| prompt-executor-openrouter-client | `ai.koog:prompt-executor-openrouter-client` | `1.3.0` (stable, 模块级无 `isBeta`) | 是 (`OpenRouterClientSettings.baseUrl`) | 否 |
| prompt-executor-openai-client-base | `ai.koog:prompt-executor-openai-client-base` | `1.3.0` (stable) | 公共基类 | 否 |
| prompt-executor-deepseek-client | `ai.koog:prompt-executor-deepseek-client` | `1.3.0-beta` (`isBeta=true`) | 是 (`DeepSeekClientSettings.baseUrl`) | 否 |
| prompt-executor-google-client | `ai.koog:prompt-executor-google-client` | `1.3.0-beta` | 是 (`GoogleClientSettings.baseUrl`) | 否 |
| prompt-executor-mistralai-client | `ai.koog:prompt-executor-mistralai-client` | `1.3.0-beta` | 是 (`MistralAIClientSettings.baseUrl`) | 否 |
| prompt-executor-dashscope-client | `ai.koog:prompt-executor-dashscope-client` | `1.3.0-beta` | 是 (`DashscopeClientSettings.baseUrl`) | 否 |
| prompt-executor-bedrock-client | `ai.koog:prompt-executor-bedrock-client` | `1.3.0` (stable) | 否 (AWS 托管端点) | 否 |
| prompt-executor-litert-client | `ai.koog:prompt-executor-litert-client` | `1.3.0-beta` | 本地, 不适用 | 否 |
| prompt-executor-llms-all | `ai.koog:prompt-executor-llms-all` | `1.3.0-beta` | 聚合工厂 (`simpleOpenAIExecutor` 等) | 否 |

**重试能力只有一处**: `ai.koog.prompt.executor.clients.retry.RetryingLLMClient` + `RetryConfig` (装饰器, `prompt-executor-clients` stable 模块)。**1.1.1 就已存在, 与 1.3.0 完全一致** (`git diff 1.1.1..HEAD -- .../clients/src/` 输出为空)。scoop-ai 目前用的是自研 `InterceptingHttpClient` + `HTTP_RETRY_CONFIG` (5 次重试, 30s 上限), Koog 的 `RetryConfig.DEFAULT` 是 3 次 / 30s 上限, 两者语义接近但不等价 (Koog 按错误消息正则匹配, scoop-ai 按 HTTP 状态码)。升级不改变这一点。

### 3.2 我们 6 个 provider 在 HEAD 的默认端点/头/路径 (与 1.1.1 逐字节相同)

| provider | client settings | 默认 baseUrl | chat path | 鉴权头 | 1.1.1 -> 1.3.0 是否变化 |
|---|---|---|---|---|---|
| deepseek | `DeepSeekClientSettings` | `https://api.deepseek.com` | `chat/completions` | `Authorization: Bearer` | **无变化** |
| openai | `OpenAIClientSettings` | `https://api.openai.com` | `v1/chat/completions` | `Authorization: Bearer` | **无变化** |
| anthropic | `AnthropicClientSettings` | `https://api.anthropic.com` | `v1/messages` | `x-api-key` + `anthropic-version: 2023-06-01` | **无变化** |
| ollama | `OllamaClient` | `http://localhost:11434` | `api/chat` | 无 | **无变化** |
| openrouter | `OpenRouterClientSettings` | `https://openrouter.ai` | `api/v1/chat/completions` | `Authorization: Bearer` | **无变化** |
| openai-compatible | `OpenAIClientSettings` / `DeepSeekClientSettings` | 用户必填 | scoop-ai 内部固定 `v1/chat/completions` | `Authorization: Bearer` | **无变化** |

> 特别注意 (与 `.scratch/multi-provider/spec.md` 第 4 节的协议表一致): openrouter 的 baseUrl 是 `https://openrouter.ai` (无 `/api/v1`), path 自带 `api/v1/...`; 因此 openrouter **不能**套用剥 `/v1` 的 `normalizeBaseUrl`, spec 的处理是正确的。

### 3.3 HEAD 相对 1.1.1 新增/变化且与多 provider 相关的**唯一**实质能力

- 新模型定义 (#2230): Anthropic `Sonnet_5` / `Opus_5` / `Opus_4_8`, OpenAI `GPT5_6*`, Google `Gemini3_*`, DashScope `QWEN3_*`, Mistral `Ministral*`, DeepSeek `DeepSeekV4FlashVisionExp`, OpenRouter 新条目。
- **Koog 内部没有任何 "provider -> 端点元数据" 的公开表**。T1 要做的 `ProviderEndpoints` 协议表无法从 Koog 复用, 仍然要手写。这一点 1.1.1 和 1.3.0 完全一样。

---

## 4. workaround 退休表 (逐条核对 HEAD 源码)

| workaround (scoop-ai 侧) | 触发场景 | HEAD/Koog 1.3.0 是否原生处理 | 证据 | 判定 |
|---|---|---|---|---|
| `stripToolChoice` (请求侧删 `tool_choice`) | 部分 openai-compatible 网关拒绝 `tool_choice: required` | **否** | `AIAgentSubgraphExt.kt:665` 与 `AIAgentFunctionalContextBaseCommon.kt:703` 在 `subgraphWithTask` / `subtask` 里 **无条件** `setToolChoiceRequired()`; `AbstractOpenAILLMClient.kt:200,255` 无条件 `toolChoice = prompt.params.toolChoice?.toOpenAIToolChoice()`, **不查 `model.capabilities`** | **仍需** |
| `reasoningFallbackToContent` (响应侧把 `reasoning_content` 塞进 `content`) | 网关返回 `content: null` + 仅 `reasoning_content`, Koog 解出 `parts=[]`, subgraph 抛 "must always call tools" | **否** | 非流式 `OpenAIMessage.Assistant.reasoningContent` (OpenAIDataModels.kt:132) 早在 1.1.1 就存在, Koog 会转成 `MessagePart.Reasoning`; 但 agents-core (含 subgraph 边匹配) tree diff 为空, **纯 reasoning 助手消息仍不匹配任何边**。1.2.0 的 #2190 只补了 **流式 delta**, 不解决非流式 "无文本无工具" 场景 | **仍需** |
| `malformedFunctionCallRetryHint` | `finish_reason: "malformed_function_call"` 且无 tool_calls/无 content | **否** | 全仓 `rg "malformed_function_call"` **零命中** (Koog 完全不认识这个 finish_reason) | **仍需** |
| `normalizeMissingCreated` | 网关省略必填 `created` 字段 | **否** | `OpenAIDataModels.kt:55` `public val created: Long` (非空), 1.1.1 -> HEAD 该行未变; DeepSeek/DashScope 的 `override val created: Long` 同样非空 | **仍需** |
| `LenientStringFinishTool` (`FinishTool<String>` 对对象 result 的容错) | 模型把 manifest 对象而非字符串回传, Koog 抛 `IndexOutOfBoundsException(-1)` | **否** | `AIAgentSubgraphExt.kt` 的 `FinishTool.decodeOutput` 仍是严格 `json.decodeFromJsonElement<FinishResult<Output>>`, 无对象/字符串双形态分支; agents-core tree diff 为空 | **仍需** |
| `fixDoubleEncoding` | tool call arguments 二次编码 | **是 (已修)** | 1.1.1 (#2096) 已修, scoop-ai 侧已删除 | **可删 (已删)** |
| `deepseek` 特判 / `isDeepSeekModelName` / `downgradeToolChoice` | openai-compatible 能力层 DeepSeek 特判 | **是 (已删)** | scoop-ai 侧已清理, Koog 侧无相关残留 | **可删 (已删)** |

**退休统计: 5 个现存 workaround, 升级到 1.3.0 后可退休 0 个。**

---

## 5. 升级成本与风险评估

### 5.1 依赖坐标变化

| 依赖 | 现在 | 升到 1.3.0 | 说明 |
|---|---|---|---|
| `ai.koog:koog-agents` | `1.1.1` | `1.3.0` | stable |
| `ai.koog:agents-test` | `1.1.1` | `1.3.0` | stable |
| `ai.koog:prompt-executor-deepseek-client` | `1.1.1-beta` | `1.3.0-beta` | beta, 必须与 stable 同版本号 (Koog 文档: "A stable module at version X.Y.Z is always compatible with a beta module at version X.Y.Z-beta") |
| `ai.koog:prompt-executor-openrouter-client` | `1.1.1` | `1.3.0` | stable (模块级无 `isBeta`) |
| `ai.koog:prompt-executor-anthropic-client` | `1.1.1` | `1.3.0` | stable |
| `ai.koog:prompt-executor-ollama-client` | `1.1.1` | `1.3.0` | stable |
| `ai.koog:http-client-java` | `1.1.1` | `1.3.0` | stable |

改动面: `gradle/libs.versions.toml` **1 行** (`koog = "1.3.0"`) + **1 行** (`koog-deepseek-client` 版本改 `1.3.0-beta`)。其余 5 个 artifact 都通过 `libs.versions.koog` / `libs.versions.koog.get()` 自动跟随, 不需要改。

### 5.2 工具链 / 运行时兼容

| 维度 | Koog 1.1.1 | Koog 1.3.0/HEAD | scoop-ai 现状 | 结论 |
|---|---|---|---|---|
| Kotlin 编译器 | 2.3.10 | **2.3.10 (未变)** | 2.1.21 | 一样。scoop-ai 根 `build.gradle.kts` 的 `-Xskip-metadata-version-check` workaround **继续有效且继续必需**, 无新增摩擦 |
| kotlinx-serialization | 1.10.0 | **1.10.0 (未变)** | 1.8.1 (Gradle 解析到 1.10.0) | 无变化 |
| kotlinx-coroutines | 1.10.2 | 1.10.2 | 1.10.2 | 无变化 |
| ktor3 | 3.3.3 | 3.3.3 | 全部 exclude (`io.ktor`) | 无变化 |
| JDK | 17 | 17 | `jvmToolchain(17)` / `JvmTarget.JVM_17` | 无变化 |
| `koog-agents` 依赖图 | - | stable 模块集合与 1.1.1 **完全一致** (`included` 集合 diff 为空, 只多了 `skills` 到 beta 列表) | - | 现有 exclude 规则 (bedrock / opentelemetry / serialization-jackson / http-client-ktor / io.ktor) 继续够用, **不需要新增 exclude** |

### 5.3 迁移工作量 (按文件)

| 文件 | 改动量 | 说明 |
|---|---|---|
| `gradle/libs.versions.toml` | 2 行 | 版本号 |
| `core/**` (17 个源文件 import koog) | **0 行** | agents-core API 零变化 |
| `tools/**` (20 个源文件 import koog) | **0 行** | client 模块 API 零变化 |
| `cli/**` (2 个源文件 import koog) | **0 行** | - |
| `build.gradle.kts` (根) | **0 行** | metadata workaround 继续保留 |
| `AGENTS.md` / `CHANGELOG.md` / `docs/providers.md` | 少量文案 | 记录新版本号 + "workaround 仍未退休" 的复核结论 |
| 合计 | **< 5 行有效代码** | 但需要完整跑一遍验证流程 |

### 5.4 测试影响

- 现有测试规模: **87 个测试文件, 1340 个 `@Test`** (core 831 / tools 189 / cli 320)。其中直接依赖 Koog 行为的: `core/.../RequestInterceptorsTest.kt` (15)、`core/.../ResponseInterceptorsTest.kt` (34)、`core/.../LenientStringFinishToolTest.kt` (37)、`core/.../GraphStrategyApiVerificationTest.kt` (4)、`core/.../ThinkingCompatibleStrategyMockTest.kt` (3)、`core/.../ScoopManifestGeneratorMockTest.kt` (60)、`tools/.../LlmProviderFactoryTest.kt` (18)、`tools/.../ReasoningRetryStubGatewayTest.kt` (2)。
- 预期影响: **零测试需要改写** (所依赖的 API 与行为均未变)。但需要重跑全部 1340 个测试 + 三阶段真实 LLM 冒烟 + 安装验证 + 与官方 manifest 逐字段比对 (AGENTS.md 规定的三步验证)。
- 新增风险点: `ReasoningRetryStubGatewayTest` 里 "Koog 1.1.1 不再二次编码" 的回归断言 (断言真实请求体不含二次编码特征) 在 1.3.0 上语义不变, 但**值得保留并重新验证** -- 这是升级中最有价值的一条回归证据。

### 5.5 风险清单

| 风险 | 等级 | 说明 / 缓解 |
|---|---|---|
| 1.3.0 / 1.3.0-beta artifact 未发布到 Maven Central 或拉取失败 | **中** | 本地 Gradle 缓存只有 `1.1.1` / `1.1.1-beta` (`C:/Users/xujl/.gradle/caches/modules-2/files-2.1/ai.koog/`), 升级需要联网拉新坐标。升级前先跑一次 `gradlew :core:dependencies --configuration jvmRuntimeClasspath` 验证可解析 |
| 升级与多 provider 同时改动, 故障归因困难 | **中** | 两者混在一起时, 一旦 openai-compatible 回归, 无法区分是 Koog 行为变化还是 T1 改动 |
| beta 模块 (`deepseek-client 1.3.0-beta`) 无 ABI 保护 | 低 | Koog 明确 "beta 模块 API 可能变"; 但本次 diff 显示 deepseek-client 只加了流式 reasoning 分支, 构造签名未动 |
| 全量验证成本 (1340 测试 + 真实 LLM 冒烟 + 安装验证) | 低-中 | 约半天工作量 |
| Kotlin metadata 版本 | **无新增** | Koog 1.1.1 与 1.3.0 都是 Kotlin 2.3.10 编译, `-Xskip-metadata-version-check` 继续兜底 |
| 运行时行为漂移 (流式 reasoning 上报) | 低 | 三个 OpenAI 系 client 流式路径新增 `emitReasoningDelta`; scoop-ai 当前 `openai-compatible` 走的是 **非流式** 执行路径, 不受影响; 若将来接流式需重测 |

---

## 6. 结论与推荐

### 6.1 推荐: **不要在本次 "多 Provider" 工作里顺带升级 Koog**

理由 (按权重排序):

1. **升级对多 provider 目标零收益**。多 provider 需要的全部能力 (per-provider baseUrl 覆盖、anthropic/ollama 重试链、端点协议表) 在 **1.1.1 里就已经具备**:
   - `AnthropicClientSettings.baseUrl` / `OllamaClient(baseUrl=)` / `DeepSeekClientSettings.baseUrl` / `OpenAIClientSettings.baseUrl` / `OpenRouterClientSettings.baseUrl` 全在 1.1.1 可用 (且 1.3.0 一字未改);
   - `RetryingLLMClient` / `RetryConfig` 装饰器在 1.1.1 已存在 (1.3.0 一字未改);
   - Koog 不提供 provider 端点元数据表, 1.3.0 也没有 -> `ProviderEndpoints` 无论如何都要手写。
2. **升级不能退休任何一个现存 workaround**。5 个 workaround (stripToolChoice / reasoningFallbackToContent / malformedFunctionCallRetryHint / normalizeMissingCreated / LenientStringFinishTool) 在 1.3.0 上 **逐条核对仍需保留**。升级不带来"删代码"的收益。
3. **破坏性变更 = 0, 所以升级收益 = 0, 风险 = 验证成本**。scoop-ai 依赖的 agents-core / agents-test / http-client / prompt-llm / prompt-model / prompt-executor-model / prompt-executor-clients 公共层在 1.1.1 -> 1.3.0 之间 **tree diff 为空**。既然没有 API 变化, 升级就只剩 "重新验证一遍" 的成本, 而没有对应的功能收益。
4. **变更解耦原则**。`.scratch/multi-provider/spec.md` 第 2 节已明确"不升级 Koog 版本"。多 provider 是一次面向用户的功能变更 (6 provider 全链路 + 宿主 GUI), Koog 升级是一次基础设施变更。两者混做, 一旦 openai-compatible 出现回归 (当前是最高优先风险, spec 第 7 节), 归因成本极高。
5. **HEAD 不是发布版**。Koog HEAD (`develop`) 是 1.3.0 之后的未发版状态 (`git diff --stat 1.3.0..HEAD` 只有 Langfuse / Bedrock runtime / koog-ktor SSE 三处)。如果要升, 目标应当是 **tag `1.3.0`**, 而不是 HEAD。

### 6.2 具体建议

| 项 | 建议 |
|---|---|
| 本次多 provider (T1..T5) | **保持 Koog 1.1.1 + deepseek-client 1.1.1-beta**。T1 的 `ProviderEndpoints` 协议表以 Koog **1.1.1** 的 client settings 为准 (已核对: 1.3.0 完全相同, 因此协议表**不会**因将来升级而失效) |
| Koog 升级 | **单独立项**, 排在多 provider 交付并稳定之后。目标版本: **`1.3.0` (tag, 不是 HEAD)** |
| 升级工单内容 | 仅 3 件事: (a) `libs.versions.toml` 改 `koog = "1.3.0"` + `koog-deepseek-client = "1.3.0-beta"`; (b) 跑全量 1340 测试; (c) 跑 AGENTS.md 三步验证 (三阶段冒烟 / 安装测试 / 官方 manifest 比对) |
| 升级验收门槛 | 现有 `ReasoningRetryStubGatewayTest` 的"无二次编码"回归断言必须继续通过; 5 个 workaround 的行为**不得**变化 (即升级本身不删任何 workaround); openai-compatible 用户行为零变化 |
| 若将来考虑升级到 1.4+ | 先看 Koog 是否有 `tool_choice` capability gating / `created` 可空 / `FinishTool` 对象容错 / `malformed_function_call` 处理的 PR, 有则升级可顺带退休对应 workaround, 才值得为升级单独排期 |

### 6.3 若仍决定升级 (备选路径)

推荐步骤顺序:

1. 在独立分支做纯版本号改动 (`gradle/libs.versions.toml` 两行), 不夹带任何功能改动。
2. `gradlew.bat :core:cleanTest :core:test --rerun-tasks` + `:tools:test` + `:cli:test` 全绿 (1340 个)。
3. 重点复核 4 条 Koog 敏感回归: `RequestInterceptorsTest` (15) / `ResponseInterceptorsTest` (34) / `LenientStringFinishToolTest` (37) / `ReasoningRetryStubGatewayTest` (2) -- 断言内容**不应**需要修改, 若需要修改说明行为真的变了, 必须逐条人工确认。
4. AGENTS.md 三步验证: 三阶段真实 LLM 冒烟 (三阶段全成功) -> 安装测试 -> 与官方 manifest 逐字段比对。
5. 更新 `AGENTS.md` 的 workaround 清单 (逐条复核 "仍需" 结论) + `CHANGELOG.md` + `docs/providers.md` 的版本号引用。
6. 确认 `koog-agents:1.3.0` 的依赖图没有引入需要新增 exclude 的模块 (当前 diff 显示 stable 模块集合未变, 预期无需改 `configurations.all`)。

---

## 7. 关键事实索引

### Koog 仓 (C:/src/kotlin/koog)

- `git diff --stat 1.1.1..HEAD` -> 85 files changed
- `git diff --stat 1.1.1..HEAD -- agents/agents-core/ agents/agents-test/ http-client/ prompt/prompt-llm/ prompt/prompt-model/ prompt/prompt-executor/prompt-executor-model/ prompt/prompt-executor/prompt-executor-clients/src/` -> **空** (零变化)
- `agents/agents-core/src/commonMain/kotlin/ai/koog/agents/ext/agent/AIAgentSubgraphExt.kt:114` `FinishTool` 类定义; 同文件 `:665` `setToolChoiceRequired()` (无条件)
- `agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/context/AIAgentFunctionalContextBaseCommon.kt:703` `setToolChoiceRequired()` (无条件)
- `agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/session/AIAgentLLMWriteSessionCommon.kt:413` `setToolChoiceRequired()` 实现
- `prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/base/AbstractOpenAILLMClient.kt:127` `createConfiguredHttpClient`; `:200,255` 无条件透传 `toolChoice`; `:421` `toOpenAIToolChoice`
- `.../openai/base/models/OpenAIDataModels.kt:55` `created: Long` (非空); `:132` `Assistant.reasoningContent`; `:962` `OpenAIStreamDelta.reasoningContent` (1.2.0 新增)
- `prompt/prompt-executor/prompt-executor-clients/src/commonMain/kotlin/ai/koog/prompt/executor/clients/retry/RetryingLLMClient.kt` + `RetryConfig.kt` (1.1.1 与 1.3.0 完全一致)
- `prompt/.../prompt-executor-openrouter-client/.../OpenRouterLLMClient.kt:49` `baseUrl = "https://openrouter.ai"`, `:50` `chatCompletionsPath = "api/v1/chat/completions"`
- `prompt/.../prompt-executor-anthropic-client/.../AnthropicLLMClient.kt:75` `baseUrl = "https://api.anthropic.com"`, `:130` `anthropic-version` 头
- `prompt/.../prompt-executor-ollama-client/.../OllamaClient.kt:82` `DEFAULT_MESSAGE_PATH = "api/chat"`
- `prompt/.../prompt-executor-deepseek-client/.../DeepSeekLLMClient.kt:42-47` `DeepSeekClientSettings`
- `koog-agents/build.gradle.kts:73-88` `betaModules` (deepseek / google / mistralai / openrouter / dashscope / litert / llms-all); `:90+` `included` (stable 集合, 1.1.1 -> HEAD 未变)
- `docs/docs/module-versioning.md:106` openrouter = `1.3.0` (stable); `:155-160` deepseek = `1.3.0-beta`
- `build.gradle.kts:90-98` beta 模块版本加 `-beta` 后缀的发布规则
- `gradle/libs.versions.toml` kotlin = 2.3.10, kotlinx-serialization = 1.10.0 (1.1.1 tag 与 HEAD 一致)

### scoop-ai 仓 (C:/Seafile/Works/kotlin/scoop-ai)

- `gradle/libs.versions.toml:3` `koog = "1.1.1"`; `:16` `koog-deepseek-client = "1.1.1-beta"`
- `build.gradle.kts:8-14` `-Xskip-metadata-version-check` (针对 Koog 的 Kotlin 2.3 metadata)
- `tools/src/jvmMain/kotlin/com/scoopai/tools/llm/LlmProviderFactory.kt` 6 provider 分支 + `stripToolChoice` + 三个响应改写器
- `core/src/commonMain/kotlin/com/scoopai/core/llm/ResponseInterceptors.kt` `reasoningFallbackToContent` / `malformedFunctionCallRetryHint` / `normalizeMissingCreated`
- `core/src/commonMain/kotlin/com/scoopai/core/agent/LenientStringFinishTool.kt` `FinalizePayload` + `LenientStringFinishTool`
- `core/src/commonMain/kotlin/com/scoopai/core/llm/RequestInterceptors.kt` `stripToolChoice`
- `.scratch/multi-provider/spec.md` 第 2 节已锁定 "不升级 Koog"
- 测试规模: 87 个测试文件 / 1340 个 `@Test` (core 831, tools 189, cli 320); 49 个文件 import `ai.koog`
