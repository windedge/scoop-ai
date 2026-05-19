# 计划：将三阶段 Agent 改为 Koog 显式图编排

将 scoop-ai 的三阶段 agent 从手动 Kotlin 代码串联（3 个独立 AIAgent + if/return 分支）改为 Koog 显式图编排。用 `strategy` DSL 定义 research/analyze/generate 三个子图节点，用 `edge` + `onCondition` 实现自动路由（包括"无 Windows 资产"的条件退出），取代当前 `ScoopManifestGenerator` 中的手工分支逻辑。

## 前置验证结果

基于 Koog 1.0.0-preview 源码（`C:/src/kotlin/koog/`）确认以下 API 全部可用：

| API | 来源文件 | 状态 |
|-----|---------|------|
| `strategy<String, String>(name) { }` | `AIAgentGraphStrategyBuilder.kt` | ✅ |
| `subgraph<Input, Output> { }` | `AIAgentSubgraphBuilder.kt` | ✅ |
| `subgraphWithTask<Input, Output>(tools, defineTask)` | `AIAgentSubgraphExt.kt` | ✅ |
| `nodeLLMRequest()` / `nodeExecuteTools()` / `nodeLLMSendToolResults()` | `AIAgentNodes.kt` | ✅ |
| `forwardTo` / `onTextMessage` / `onToolCalls` / `onCondition` / `asUserMessage` / `transformed` | `AIAgentEdges.kt` | ✅ |
| `ToolSelectionStrategy.ALL / NONE / Tools(list) / AutoSelectForTask(desc)` | `AIAgentSubgraph.kt` | ✅ |
| `subgraph(llmModel = ..., llmParams = ..., responseProcessor = ...)` | `AIAgentSubgraphBuilder.kt` | ✅ |
| `nodeLLMRequestStructured<T>(examples)` | `AIAgentNodes.kt` | ✅ |
| `subgraphWithVerification(...)` | `AIAgentSubgraphExt.kt` | ✅ |

关键发现：
- **`subgraphWithTask`** 是高级封装：自动创建 ReAct 循环 + `finalize_task_result` 工具，LLM 通过调用 finish tool 返回结构化结果。适合"单一任务 → 结构化输出"的场景
- **`subgraph(llmParams = ...)`** 支持 per-subgraph 的 LLM 参数注入，可直接用于 DeepSeek thinking 参数配置
- **`subgraph(tools = [...])` / `ToolSelectionStrategy.Tools([...])`** 支持 per-subgraph 工具子集限定

## 范围

- 包含：
  - 用 `strategy<String, String>` DSL 定义三阶段图工作流
  - 每个阶段用 `subgraph` 封装，通过 `tools` 参数限定工具子集
  - 用 `edge` + `onCondition` 替代当前 `if (status == "no_windows_assets") return` 手工逻辑
  - 阶段间数据传递改为图上下文（`storage`），不再用外部变量
  - 把 `ScoopManifestGenerator` 从"调用 3 个 AIAgent" 改为"创建 1 个 GraphAIAgent"
- 不包含：
  - 不改动 `LlmProviderFactory.kt` / DeepSeek workaround
  - 不改动 `PhaseOutput.kt` / `SystemPrompts.kt` / `ReferenceManifests.kt`
  - 不改动 tools 层（`FileManifestIo` / `DefaultToolRegistryFactory` 等）
  - 不改动 CLI 入口 `Main.kt`
  - 不添加历史压缩或持久化特性（后续迭代）

## 关键文件

- `core/src/main/kotlin/com/scoopai/core/ScoopManifestGenerator.kt` — 当前三阶段主流程，需要完全重写为图策略
- `core/src/main/kotlin/com/scoopai/core/agent/ScoopManifestStrategy.kt`（**新建**）— 图策略定义：节点、边、条件
- `core/src/main/kotlin/com/scoopai/core/agent/ThinkingCompatibleStrategy.kt` — 现有的 thinking 兼容策略，将被新图策略取代（内部 ReAct 循环逻辑复用）
- `core/src/main/kotlin/com/scoopai/core/agent/PhaseOutput.kt` — 已有的结构化 JSON data class，图节点的输入/输出类型
- `core/src/main/kotlin/com/scoopai/core/agent/SystemPrompts.kt` — 各阶段 system prompt，subgraph 中引用

## 设计方案

### 方案选择：`subgraphWithTask` vs 手动 `subgraph`

有两种实现路径：

#### 方案 A：`subgraphWithTask`（推荐）

每个阶段用 `subgraphWithTask` 封装，自动获得 ReAct 循环 + `finalize_task_result` 工具：

```kotlin
val researchPhase by subgraphWithTask<String, String>(
    tools = researchTools,  // GitHubApi + WebFetcher
    llmParams = phasePromptParams(),
    defineTask = { url -> buildResearchPrompt(url, outputPath) }
)
```

**优点**：代码量最少，Koog 内置 ReAct 循环、tool choice 强制、重试逻辑
**缺点**：LLM 必须通过 `finalize_task_result` 工具返回结果，依赖模型对 tool choice 的支持

#### 方案 B：手动 `subgraph`

每个阶段用 `subgraph` + 自定义节点 + 手动边：

```kotlin
val researchPhase by subgraph<String, String>(
    tools = researchTools,
    llmParams = phasePromptParams(),
) {
    val nodeLLM by nodeLLMRequest()
    val nodeTool by nodeExecuteToolsAndGetResults()
    val nodeSendResult by nodeLLMSendToolResults()

    edge(nodeStart forwardTo nodeLLM asUserMessage { it })
    edge(nodeLLM forwardTo nodeTool onToolCalls { true })
    edge(nodeLLM forwardTo nodeFinish onTextMessage { true })
    edge(nodeTool forwardTo nodeSendResult)
    edge(nodeSendResult forwardTo nodeFinish onTextMessage { true })
    edge(nodeSendResult forwardTo nodeTool onToolCalls { true })
}
```

**优点**：完全控制流程，可嵌入 thinking-compatible 修复逻辑
**缺点**：代码量较大，需要手动处理每条边

**决策**：由于 `thinkingCompatibleSingleRunStrategy()` 已经验证了手动 ReAct 循环 + thinking 修复在 DeepSeek 上的可靠性，且 `subgraphWithTask` 内部已处理 tool choice 强制逻辑，**推荐先用方案 A**。如果遇到 thinking 模型兼容性问题，再退回方案 B 并复用 `ThinkingCompatibleStrategy` 的修复逻辑。

### 图结构

```
nodeStart ──→ researchSubgraph ──[status=="ok"]──→ analyzeSubgraph ──→ generateSubgraph ──→ nodeFinish
                    │
                    └──[status=="no_windows_assets"]──→ nodeFinish (输出建议文本)
```

### 数据流

阶段间通过 `storage` 传递结构化 JSON：

```
researchSubgraph 输出 String (ResearchOutput JSON)
  → storage.set("research_output", json)
  → onCondition { parseStatus(storage.get("research_output")) == "ok" } → analyzeSubgraph

analyzeSubgraph 从 storage 读取 research 输出，输出 String (AnalyzeOutput JSON)
  → storage.set("analyze_output", json)

generateSubgraph 从 storage 读取 research + analyze 输出，输出最终 manifest JSON
```

### DeepSeek Thinking 参数注入

每个 `subgraph` 通过 `llmParams` 参数注入 thinking 配置：

```kotlin
llmParams = LLMParams().apply {
    // 根据 thinkingLevel 配置 additionalProperties
}
```

不再需要 `phasePromptParams()` 作为独立函数。

## 可复用代码

| 当前代码 | 迁移方式 |
|---------|---------|
| `parseResearchOutput()` / `parseAnalyzeOutput()` | 迁移到图策略中的 transformation node |
| `extractJson()` / `isValidJson()` | 已改为 `internal`，在 node 中直接调用 |
| `validateAndFix()` | 保留在 generate subgraph 中作为自定义节点 |
| `phasePromptParams()` | 转化为 `subgraph(llmParams = ...)` 参数 |
| `buildResearchPrompt()` / `buildAnalyzePrompt()` / `buildGeneratePrompt()` | 迁移到 `subgraphWithTask(defineTask = { ... })` |
| `ResearchOutput` / `AnalyzeOutput` | 不变，作为 storage 传递的结构化类型 |
| `thinkingCompatibleSingleRunStrategy()` 的 retry 逻辑 | 如果用方案 A 则不需要（`subgraphWithTask` 内部处理）；方案 B 则复用 |

## 依赖库情况

不需要引入任何新库。当前 `koog-agents:1.0.0-preview` 聚合包已传递依赖所有必需模块。

## 实施步骤

### 步骤 1：验证 `subgraphWithTask` 在 scoop-ai 环境中的可用性

- 文件：`core/src/test/kotlin/...`
- 变更：用 `getMockExecutor` 写一个最小编译+运行测试，验证 `subgraphWithTask` 能在 scoop-ai 的依赖版本中正常工作
- 验收：测试通过，`finalize_task_result` 工具被正确调用

### 步骤 2：创建 `ScoopManifestStrategy.kt` 骨架

- 文件：`core/src/main/kotlin/com/scoopai/core/agent/ScoopManifestStrategy.kt`（新建）
- 变更：创建图策略函数，定义三个 subgraph + 条件边

```kotlin
fun scoopManifestStrategy(
    tools: ToolRegistry,
    manifestIo: ManifestIo,
    llmParams: LLMParams,
    maxIterations: Int = 10,
) = strategy<String, String>("scoop_manifest_generator") {
    val researchSubgraph by subgraphWithTask<String, String>(
        name = "research",
        tools = researchTools,
        llmParams = llmParams,
        defineTask = { url -> buildResearchPrompt(url) }
    )
    val analyzeSubgraph by subgraphWithTask<String, String>(
        name = "analyze",
        tools = analyzeTools,
        llmParams = llmParams,
        defineTask = { researchJson -> buildAnalyzePrompt(researchJson) }
    )
    val generateSubgraph by subgraphWithTask<String, String>(
        name = "generate",
        tools = generateTools,
        llmParams = llmParams,
        defineTask = { analyzeJson -> buildGeneratePrompt(analyzeJson) }
    )

    // 路由边
    edge(nodeStart forwardTo researchSubgraph)
    edge(researchSubgraph forwardTo analyzeSubgraph onCondition { parseStatus(it) == "ok" })
    edge(researchSubgraph forwardTo nodeFinish onCondition { parseStatus(it) != "ok" })
    edge(analyzeSubgraph forwardTo generateSubgraph)
    edge(generateSubgraph forwardTo nodeFinish)
}
```

- 验收：编译通过

### 步骤 3：实现 research subgraph 的完整逻辑

- 文件：`ScoopManifestStrategy.kt`
- 变更：
  - 定义 `researchTools` 子集（GitHubApi + WebFetcher + AskUser 的 descriptors）
  - 实现 `buildResearchPrompt()`（从 `ScoopManifestGenerator` 迁移）
  - 实现 research 结果解析 → storage 写入
  - 实现 thinking 参数注入
- 验收：research 阶段能独立运行并输出结构化 JSON

### 步骤 4：实现 analyze + generate subgraph

- 文件：`ScoopManifestStrategy.kt`
- 变更：
  - 定义 `analyzeTools`（HashComputer + ArchiveInspector）和 `generateTools`（ManifestIO）
  - 从 storage 读取上游输出构建 prompt
  - 迁移 `validateAndFix` 到 generate subgraph 中
- 验收：三阶段可串联运行

### 步骤 5：重写 `ScoopManifestGenerator` 为使用图策略

- 文件：`ScoopManifestGenerator.kt`
- 变更：
  - `generate()` 方法创建一个 `AIAgent<String, String>`（使用 `scoopManifestStrategy`）并 run
  - 删除 `executePhase()` / `runPhaseWithRetry()` / 手工 `if/return` 分支
  - 保留 `extractJson()` / `isValidJson()` / `extractManifestJsonFromToolArgs()` 作为工具方法
  - 添加 `handleEvents` feature 用于日志和 manifest 捕获
- 验收：编译通过，图 agent 可执行完整流程

### 步骤 6：更新测试

- 文件：测试文件
- 变更：
  - 更新 `ScoopManifestGeneratorMockTest` 适配新的图策略接口
  - 可选用 Koog `testGraph` DSL 做 graph structure verification
- 验收：所有测试通过

### 步骤 7：端到端验证

- 验收：
  1. 编译成功
  2. 跑 ripgrep（CLI 包），三阶段正常
  3. 跑 WinMerge（GUI 包），三阶段正常
  4. 跑 zerostack（无 Windows 资产），条件边正确跳过 analyze/generate
  5. 安装测试通过

## 依赖关系

```
步骤1 (API验证) ──→ 步骤2 (骨架) ──→ 步骤3 (research) ──→ 步骤4 (analyze+generate)
                                                                    │
                                                              步骤5 (重写Generator)
                                                                    │
                                                              步骤6 (更新测试)
                                                                    │
                                                              步骤7 (端到端)
```

## 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| `subgraphWithTask` 内部强制 `setToolChoiceRequired()`，DeepSeek thinking 模式可能不兼容 | 高 | 退回方案 B（手动 subgraph），复用 `thinkingCompatibleSingleRunStrategy` 的修复逻辑 |
| `subgraphWithTask` 的 `finalize_task_result` 工具 schema 是自动生成的，对于 String 输出类型可能有问题 | 中 | FinishTool 内部用 `FinishResult<T>` 包装了原始类型，应能处理 String |
| 阶段间通过 `storage` 传递数据，类型安全较弱 | 低 | 用 JSON 序列化 + 明确的 key 名称约定 |
| `subgraph` 的 `llmParams` 注入是否能覆盖 system prompt 中的 thinking 参数 | 中 | 需要在步骤 1 验证 |
| 现有 `handleEvents` 中 manifest 捕获逻辑的迁移 | 低 | 用 `onToolCallCompleted` 事件拦截 `validate`/`write` tool 的结果 |
| `subgraphWithTask` 对不支持 `LLMCapability.ToolChoice` 的模型有 fallback 机制（最多重试 3 次），但可能不够 | 中 | 可通过 `assistantResponseRepeatMax` 参数调整 |

## 待确认问题

1. ~~Koog 0.8.0 是否完整支持 `strategy {}` DSL + `subgraph {}` 函数？~~ **已确认：Koog 1.0.0-preview 完整支持**
2. ~~subgraph 间通过 `storage` 传递序列化 JSON 是否是 Koog 推荐的数据传递方式？~~ **已确认：`storage` 是 `AIAgentSubgraph` 的标准数据传递机制，key 通过 `createStorageKey()` 创建**
3. `subgraphWithTask` 的 `defineTask` lambda 中如何访问 `ScoopManifestGenerator` 的 `outputPath` 等外部参数？**方案：通过闭包捕获，或用 `storage.set()` 在 nodeStart 之前写入**
4. 现有 `thinkingCompatibleSingleRunStrategy()` 中的纯推理重试逻辑，在图策略模式下是否还需要？**取决于方案选择：方案 A 由 `subgraphWithTask` 内部处理；方案 B 需要复用**

## 验证命令

```bat
:: 编译
cd C:/Seafile/Works/kotlin/scoop-ai && cmd //c gradlew.bat :cli:shadowJar

:: 运行测试
cd C:/Seafile/Works/kotlin/scoop-ai && cmd //c "gradlew.bat :core:cleanTest :core:test --rerun-tasks"

:: 跑 ripgrep（CLI 包）
cd C:/Seafile/Works/kotlin/scoop-ai && cmd //c "set SCOOP_AI_PROVIDER=openai-compatible&& set SCOOP_AI_API_KEY=sk-xxxx&& set SCOOP_AI_BASE_URL=http://192.168.40.130:8317&& set SCOOP_AI_MODEL=opencode/deepseek-v4-flash&& java -jar cli/build/libs/cli-0.1.0-all.jar https://github.com/BurntSushi/ripgrep"

:: 对比官方
git diff --no-index bucket/ripgrep.json C:/Users/xujl/scoop/buckets/main/bucket/ripgrep.json
```
