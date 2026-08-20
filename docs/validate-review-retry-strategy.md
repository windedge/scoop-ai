# Validate + Review 拆分为 Strategy 节点方案

## 背景

当前 generate 阶段的 validate 和 review 都是 **LLM 自愿调用**的工具。LLM 可以忽略 validate 的错误或 review 的建议，导致：

1. **xdelta**：`pre_install Rename-Item` 版本化 exe 的问题反复出现
2. **laragon/superf4**：GUI 程序多余的 `bin` 没被删除
3. **laragon**：`env_set` 多余（已部分修复，但 bin 仍多余）

## 方案：用 `subgraphWithRetry` 包装 generate

利用 Koog 的 `subgraphWithRetry` API，将 validate/review 作为 **强制条件检查**，失败自动回环重试。

### 架构图

```
nodeStart → research(LLM) → analyze(LLM) → prepareManifest → generateWithRetry → nodeFinish
                                                       ↑
                                         ┌─────────────┴──────────────────────────┐
                                         │ subgraphWithRetry(maxRetries=2)        │
                                         │                                        │
                                         │  ┌─ Retry #1 ────────────────────────┐ │
                                         │  │  action:                           │ │
                                         │  │    subgraphWithTask(generateTools) │ │
                                         │  │      LLM → tools → finalize        │ │
                                         │  │      → manifest v1                  │ │
                                         │  │  decide:                           │ │
                                         │  │    validate(v1) → ERROR            │ │
                                         │  │    → Reject(feedback)              │ │
                                         │  └───────────────────────────────────┘ │
                                         │                    │                    │
                                         │                    │ 回环               │
                                         │                    ↓                    │
                                         │  ┌─ Retry #2 ────────────────────────┐ │
                                         │  │  action (context restored):        │ │
                                         │  │    subgraphWithTask(generateTools) │ │
                                         │  │      LLM + feedback → finalize     │ │
                                         │  │      → manifest v2                  │ │
                                         │  │  decide:                           │ │
                                         │  │    validate(v2) → valid ✓          │ │
                                         │  │    review(v2) → approved ✓         │ │
                                         │  │    → Approve                       │ │
                                         │  └───────────────────────────────────┘ │
                                         │                    │                    │
                                         │                    ↓                    │
                                         │              cleanup → nodeFinish       │
                                         └────────────────────────────────────────┘
```

### LLM 会话堆栈（重试时）

```
Retry #1:
  LLM Session [ctx_0]
  ├── system: "You are manifest generator..."
  ├── user: "<skeleton JSON>"
  └── assistant: finalize_task_result → "{manifest v1 with pre_install Rename-Item...}"

  decide:
    validate("{manifest v1}") → ERROR: "pre_install Rename-Item for versioned exe..."
    → ConditionResult.Reject("pre_install Rename-Item... Use per-arch bin instead")

Retry #2 (context restored + feedback injected):
  LLM Session [ctx_0 + feedback]
  ├── system: "You are manifest generator..."
  ├── user: "<skeleton JSON>"
  ├── assistant: "{manifest v1 with pre_install Rename-Item...}"
  ├── user: "VALIDATION FAILED: pre_install Rename-Item... Use per-arch bin instead"  ← feedback
  └── assistant: finalize_task_result → "{manifest v2 with per-arch bin [[...]]}"

  decide:
    validate("{manifest v2}") → valid ✓
    review("{manifest v2}") → approved ✓
    → ConditionResult.Approve
```

### 回环机制（来自 Koog `subgraphWithRetry` 源码）

| 环节 | 行为 |
|------|------|
| **回环输入** | 返回 `initialInput`（骨架 JSON），不是上次的 manifest 输出 |
| **上下文恢复** | `this.replace(initialContext)` → LLM 看到完整的对话历史 |
| **feedback 注入** | `llm.writeSession { user(feedback) }` → 追加到初始上下文 |
| **LLM 看到的** | 原始输入 + 自己第一次的回复 + feedback 错误信息 |
| **重试消耗** | 每次重试都是完整 LLM 调用（input tokens = 原始 + 累积 feedback） |

## 改动范围

### 1. `ScoopManifestStrategy.kt` — 核心：替换 generate 为 retry 包装

**当前**：
```kotlin
val generate by subgraphWithTask<String, String>(
    tools = generateTools,
    llmParams = llmParams,
) { manifestSkeleton ->
    generatePromptBuilder?.invoke(manifestSkeleton, lastAnalyzeOutput!!, researchData)
        ?: manifestSkeleton
}

edge(prepareManifest forwardTo generate)
edge(generate forwardTo nodeFinish transformed { ... })
```

**改为**：
```kotlin
val generateWithRetry by subgraphWithRetry<String, String>(
    condition = { manifestOutput ->
        // 1. validate: 结构性检查（JSON 合法、必要字段、pre_install rename 等）
        val validateResult = manifestIo.validate(manifestOutput)
        if (!validateResult.valid) {
            return@subgraphWithRetry ConditionResult.Reject(
                "VALIDATION FAILED: " + validateResult.errors.joinToString("; ")
            )
        }
        // 2. review: 语义检查（license、GUI vs CLI、多余 bin、checkver 等）
        val reviewResult = reviewAgent.run(manifestOutput)
        val parsed = parseReviewResult(reviewResult)
        if (!parsed.approved) {
            return@subgraphWithRetry ConditionResult.Reject(
                "REVIEW ISSUES: " + parsed.issues.joinToString("; ") { it.message }
            )
        }
        ConditionResult.Approve
    },
    maxRetries = 2,
    conditionDescription = "The manifest must pass validate (structural) and review (semantic) checks.",
) {
    // action 内部——仍然用 subgraphWithTask（不是 nodeLLMRequest）
    val generateLLM by subgraphWithTask<String, String>(
        tools = generateTools,   // 只保留 __ask_user__，移除 validate/review
        llmParams = llmParams,
    ) { skeleton ->
        generatePromptBuilder?.invoke(skeleton, lastAnalyzeOutput!!, researchData) ?: skeleton
    }
    nodeStart then generateLLM then nodeFinish
}

edge(prepareManifest forwardTo generateWithRetry)
edge(generateWithRetry forwardTo nodeFinish transformed { result ->
    val manifest = extractJsonBlock(result.output) ?: result.output
    ManifestResult(manifest, suggestedAppName)
})
```

#### 为什么 action 内部用 `subgraphWithTask` 而不是 `nodeLLMRequest`？

| | `nodeLLMRequest` | `subgraphWithTask` |
|---|---|---|
| 工具支持 | ❌ 无 | ✅ `tools + llmParams` |
| 多轮对话 | ❌ 单次调用 | ✅ LLM → tool → LLM → finalize |
| LLM params | 继承外层 | ✅ 独立配置 |
| `__ask_user__` | ❌ | ✅ |
| `finalize_task_result` | ❌ | ✅ 结构化输出 |

**必须用 `subgraphWithTask` 的理由**：

1. **`__ask_user__` 仍然需要** — generate 阶段 LLM 偶尔需要询问用户（variant 选择、InnoSetup 兼容性等）
2. **LLM params 需要独立配置** — temperature、thinking level 等
3. **`finalize_task_result` 结构化输出** — `subgraphWithTask` 内部 LLM 通过调用 `finalize_task_result` 返回 manifest，这是 Koog subgraph 的标准终止机制
4. **多轮交互** — LLM 可能需要多轮工具调用（如 `__ask_user__` → LLM 思考 → finalize）

**Koog API 兼容性**：`subgraphWithRetry` 的 `defineAction` 类型是 `AIAgentSubgraphBuilderBase<Input, Output>.() -> Unit`，与普通 subgraph 定义完全一致，因此可以在 action 内部嵌套 `subgraphWithTask`。

### 2. `ScoopManifestGenerator.kt` — 传入 validate/review 依赖

- `manifestIo` 需要从 `ToolRegistry` 中提取或作为参数传入
- `reviewAgent` 需要作为依赖传入 condition lambda

### 3. `PhasePrompts.kt` — 简化 Generate prompt

**移除**：
- "4. Self-Review: Call `__review_manifest__`..."
- "5. Validate: Call `validate`..."

**保留**：
- "1. Apply installer-specific transformations"
- "2. PRESERVE skeleton structure"
- "3. Add autoupdate.hash"

因为 validate/review 现在由策略图强制执行，LLM 不需要自己调用。

### 4. `FileManifestIo.kt` — validate 方法需可从 strategy 直接调用

当前 validate 是 `ManifestIo` 接口的方法，可以直接调用。但返回值需要解析：
- 当前返回 JSON 字符串 `{"valid":true,...}`
- condition 中需要解析为布尔值

可能需要添加一个 `validateStructured()` 方法返回 data class，或者在 condition 中解析 JSON。

### 5. `ReviewPrompts.kt` — 无需修改

Review prompt 保持不变，仍然检查 license、GUI vs CLI、checkver 等。

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| Token 消耗增加（每次重试都是完整调用） | `maxRetries=2` 限制，最多 3 次调用 |
| validate 在 condition 中调用，不是工具调用 | validate 是纯本地函数，无网络开销 |
| review 在 condition 中启动独立 agent | review agent 已有独立 token 计数 |
| LLM 第一次输出的 manifest 被丢弃 | 这是设计意图——基于 feedback 重新生成质量更高 |
| 回环恢复上下文导致 feedback 累积 | Koog 只保留初始上下文 + 最新 feedback |

## 测试计划

1. **Mock 测试**：用 `getMockExecutor` 模拟 LLM，验证 retry 逻辑
   - 第 1 次：返回带 `pre_install Rename-Item` 的 manifest → validate 报错
   - 第 2 次：返回正确的 per-arch bin manifest → validate + review 通过
2. **端到端测试**：用 xdelta 验证 `versioned_bin` 被自动修复
3. **回归测试**：现有 156+ 测试不破坏
