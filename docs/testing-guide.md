# Testing Guide

本文内容从 AGENTS.md 移出（原文照搬，仅修正已迁移的测试文件路径）。写测试或修改测试前先读本文；其中的文件清单与模式以本文为准。

## 测试命令

```bat
:: 运行全部 core 测试
gradlew.bat :core:jvmTest

:: 强制重跑（忽略缓存）
gradlew.bat :core:cleanJvmTest :core:jvmTest --rerun-tasks
```

测试结果 XML 位于 `core/build/test-results/jvmTest/`（tools 为 `tools/build/test-results/jvmTest/`，cli 为 `cli/build/test-results/test/`）。

统计测试数用 `scripts/test-report.py`，不要手写 grep/python 管道：

```bat
python scripts/test-report.py
```

### 测试文件

- `core/src/commonTest/kotlin/com/scoopai/core/llm/RequestInterceptorsTest.kt` — 请求侧拦截器测试（`stripToolChoice` 5 个 + `InterceptingHttpClient` 组合 + 单编码直通回归）
- `tools/src/jvmTest/kotlin/com/scoopai/tools/llm/ReasoningRetryStubGatewayTest.kt` — stub 网关全链路测试，含 "Koog 1.1.1 不再二次编码" 回归断言
- `tools/src/jvmTest/kotlin/com/scoopai/tools/llm/LlmProviderFactoryTest.kt` — Provider 工厂测试
- `core/src/commonTest/kotlin/com/scoopai/core/agent/PhaseOutputTest.kt` — 数据类序列化/反序列化测试，包含 FlexibleBinSerializer 的各种 bin 格式
- `core/src/commonTest/kotlin/com/scoopai/core/ScoopManifestGeneratorMockTest.kt` — 三阶段 agent 端到端 mock 测试，含 `getMockExecutor` 控制 LLM 响应

### 测试依赖

- `core/build.gradle.kts`：`commonTest` 配置了 kotlin("test") + `libs.koog.agents.test`（即 `ai.koog:agents-test`）
- `tools/build.gradle.kts`：`jvmTest` 配置了 kotlin("test") + `libs.koog.agents.test` + JUnit 5
- `cli/build.gradle.kts`：`testImplementation` 配置了 kotlin("test") + JUnit 5
- 版本定义在 `gradle/libs.versions.toml`

## PhaseOutput 序列化测试

`FlexibleBinSerializer` 处理 `AnalyzeOutput.bin` 字段的三种格式：

- `"bin": "app.exe"` → 自动包装为 `["app.exe"]`
- `"bin": ["app.exe", "alias"]` → 原样保留
- `"bin": [["app.exe", "App Name"]]` → 原样保留
- `"bin": []` / 缺省 → 空列表

数据类（`ResearchOutput`、`AnalyzeOutput`、`WindowsAsset`、`AssetEntry`、`LicenseInfo`）的 JSON 序列化/反序列化 round-trip 测试覆盖完整字段和最小字段两种场景。

## LlmProviderFactory 测试模式

`LlmProviderFactory` 是纯工厂，`defaultModel()` 和 `create()` 都可直接测试，无需 mock。测试覆盖：
- 各 provider 的默认 model
- openai-compatible 路径根据 model id 自动选择 DeepSeek/OpenAI provider
- DeepSeek model 走通用能力集，不再注入 `LLMCapability.Thinking`
- 无效参数抛异常

## 模拟 JSON 多层编码的测试技巧

Koog 序列化 tool call arguments 时会对字符串做多层 JSON 编码，测试中用 `jsonStr(jsonStr(inner))` 模拟：

```kotlin
val json = Json { ignoreUnknownKeys = true }
fun jsonStr(value: String): String =
    json.encodeToString(kotlinx.serialization.serializer<String>(), value)

val doubleEncoded = jsonStr(jsonStr("""{"url":"https://..."}"""))
val input = """{"arguments":$doubleEncoded}"""
```

> 说明：Koog 1.1.1 (#2096) 已在客户端修复 tool call arguments 的二次编码，
> `fixRequestBody` / `DoubleEncodingFixHttpClient` 等相关 workaround 已删除；
> 上述 `jsonStr(jsonStr(...))` 多层编码技巧仍可用于测试其它需要多层编码的场景。

## KoogHttpClient Mock

测试中用匿名 object 创建空操作的 `KoogHttpClient`，所有方法抛 `UnsupportedOperationException`，只测试拦截器/响应重写逻辑：

```kotlin
val noop = object : KoogHttpClient {
    override val clientName = "test"
    override fun close() {}
    // ... 所有方法 throw UnsupportedOperationException()
}
// 例如测试 ResponseInterceptors.normalizeMissingCreated / reasoningFallbackToContent：
val out = normalizeMissingCreated(rawJson, 0L)
```

## getMockExecutor 使用模式

Koog 的 `agents-test` 模块（`ai.koog:agents-test`，版本跟随 `libs.versions.koog`，当前 1.1.1）已发布到 Maven Central，可在 `core/build.gradle.kts` / `tools/build.gradle.kts` 的测试依赖中使用。

核心 API：
```kotlin
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.agents.testing.feature.withTesting

val mockLLM = getMockExecutor {
    mockLLMAnswer("Hello!") onRequestContains "pattern"
    mockLLMAnswer("Default").asDefaultResponse
    mockLLMToolCall(MyTool, MyTool.Args("...")) onRequestEquals "exact"
    mockTool(MyTool) alwaysReturns "result"
}
```

工具类必须使用 `SimpleTool` 子类（args 必须是 data class，不可用 `String` 等基础类型）：
```kotlin
@Serializable
data class EchoArgs(val message: String)

val echoTool = object : SimpleTool<EchoArgs>(
    argsSerializer = EchoArgs.serializer(),
    name = "echo",
    description = "Echo back the input",
) {
    override suspend fun execute(args: EchoArgs): String = args.message
}
```

启用测试模式：
```kotlin
val agent = AIAgent(
    promptExecutor = mockLLM,
    toolRegistry = toolRegistry,
    strategy = myStrategy,
    agentConfig = AIAgentConfig(
        prompt = prompt("test") { system("...") },
        model = someModel,
        maxAgentIterations = 5,
    ),
) {
    withTesting()
}
val result = agent.run("user input")
```

## subgraph 图策略使用模式

在 `strategy {}` DSL 中使用 `subgraph {}` 和 `then` 链式连接：

```kotlin
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.subgraph
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.asUserMessage

val myStrategy = strategy<String, String>("my_strategy") {
    val phase1 by subgraph<String, String>(name = "phase1") {
        val nodeLLM by nodeLLMRequest()
        edge(nodeStart forwardTo nodeLLM asUserMessage { it })
        edge(nodeLLM forwardTo nodeFinish onTextMessage { true })
    }
    val phase2 by subgraph<String, String>(name = "phase2", llmParams = ...) {
        val nodeLLM by nodeLLMRequest()
        edge(nodeStart forwardTo nodeLLM asUserMessage { it })
        edge(nodeLLM forwardTo nodeFinish onTextMessage { true })
    }
    nodeStart then phase1 then phase2 then nodeFinish
}
```

关键规则：
- `nodeLLMRequest()`（输入 `Message.User`）配合 `asUserMessage` 边
- `nodeLLMRequestWithUserText()`（输入 `String`）**不能**配合 `asUserMessage`（类型不匹配：`asUserMessage` 输出 `Message.User`，但目标节点要求 `String`）
- 子图间通过 `nodeStart → nodeFinish` 的 `then` 链传递 `String` 数据
