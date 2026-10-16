# 设计：每阶段返回执行操作摘要（零额外 LLM 调用）

目标：Scooper Create 界面 steps 里展示每阶段"具体执行了哪些操作"，比现有模板句更真实。
硬约束：不为生成摘要额外调用 LLM；摘要由模型在产出阶段结果时顺带生成。

## 推荐路径：C（A 为主 + B 兜底）

**A. 扩展阶段输出 schema，让模型顺带生成摘要**（主体）：

- research/analyze：`ResearchOutput` / `AnalyzeOutput` 加可选字段 `summary: List<String>? = null`。
  这两个阶段用 Koog 的 `FinishTool<T>`（koog `AIAgentSubgraphExt.kt:114`），其 tool 参数 schema 由
  `getJsonSchema(outputType)` 从 `@Serializable` 类型自动生成，加字段即自动出现在
  `finalize_task_result` 的 schema 里，无需任何 schema 注册代码。
- generate/assist/repair：`FinalizePayload` 加可选第四参数 `activities: List<String>? = null`，
  `LenientStringFinishTool` 的 descriptor `optionalParameters` 加
  `ToolParameterType.List(ToolParameterType.String)`（Koog `ToolDescriptors.kt:92` 确认存在该类型）。
  这些阶段现在就能产出自然语言（explanation 参数），顺带产出 3-6 条操作句，模型负担极低。

**B. 宿主确定性兜底**（防御）：`PhaseSummaryFormatter.formatPhaseOutput` 优先读模型摘要，
缺失/为空时 fallback 到现有模板句。现有模板逻辑原样保留，不做 B 的"细化模板"扩展
（ToolActivityFormatter 不动）。

### 为什么不选 B 单独走

ToolActivity args/result 在引擎侧已被 `sanitizeForLog(maxLen=120)` 截断（ScoopManifestGenerator.kt:291/297），
宿主拿到的 `ToolActivity.Started.toolArgs` 最多 120 字符，从里面提取 url/文件名/hash 可靠性差；
且只能拼出"模板+变量"，永远到不了"比较了 3 个 release 变体后选定 stable"这类叙述。

### 为什么不选 A 单独走

模型不填、填空串、填整个段落都可能发生。宿主必须有确定性 fallback，否则 UI 出现空 step。

## 各阶段适用性

| 阶段 | 路径 | 说明 |
|------|------|------|
| research | A（summary 字段） | 真实操作最多（fetchRepoInfo/fetchLatestRelease/读 LICENSE/搜源码），模型最有话可说 |
| analyze | A（summary 字段） | 计算了哪些 hash、inspect 了哪些 archive、为何否决某 asset |
| generate | A（activities 参数） | 无工具阶段，操作 = 对 skeleton 做的变换（NSIS url 变换、autoupdate.hash 块） |
| assist/repair | A（activities 参数） | 按用户反馈/审查 issue 改了什么字段、为什么 |
| verify | 不做 | 确定性节点（validate + review），无模型收尾调用；现有"只显示时长"约定保持（proposal 4.6） |
| no_windows_assets 早退 | 不做 | 引擎确定性合成 explanation，已有文案，不加字段 |

verify 若将来需要，可从 `StageInfo`（toolCallCount/durationMs）拼一句"validated with N checks"，
本期不做。

## 数据结构改动点（文件:行）

引擎（scoop-ai）：

1. `core/src/commonMain/kotlin/com/scoopai/core/agent/PhaseOutput.kt:54`
   `ResearchOutput` 末尾（`checksums_filename` 之后）加：
   ```kotlin
   /** 每条一句：本阶段实际执行的操作，供宿主 steps 展示。模型未填时为 null。 */
   val summary: List<String>? = null,
   ```
2. `core/src/commonMain/kotlin/com/scoopai/core/agent/PhaseOutput.kt:130`
   `AnalyzeOutput` 末尾（`suggest` 之后）加同样的 `summary` 字段。
3. `core/src/commonMain/kotlin/com/scoopai/core/agent/LenientStringFinishTool.kt:42`
   `FinalizePayload` 加 `val activities: List<String>? = null`。
4. `LenientStringFinishTool.kt` descriptor `optionalParameters`（约行 89-98）追加：
   ```kotlin
   ToolParameterDescriptor(
       name = "activities",
       description = "Optional. 3-6 short sentences, each describing one concrete action " +
           "performed in this phase (tool called, file read, field changed), in order.",
       type = ToolParameterType.List(ToolParameterType.String),
   )
   ```
5. `LenientStringFinishTool.kt` `wrapPayload`（约行 105）写出 `activities`；
   `extractPayload`（约行 145-152）加容错提取：仅接受 JSON array 且元素全部为 string literal，
   否则解码为 null（对称于 `extractOptionalText` 的容错哲学）。
6. `core/src/commonMain/kotlin/com/scoopai/core/agent/ScoopManifestStrategy.kt` 四处
   `onPhaseOutput` 调用点转发：
   - 行 262 researchDone：`ResearchOutput` 序列化后已含 `summary`，无需包装。
   - 行 470 analyze edge：同上。
   - 行 478 generate edge：现在是 `payload.manifest`（manifest JSON 字符串）。解析为
     JsonObject 后注入顶层 `"_meta": {"activities": [...]}`（解析失败则原样透传），再转发。
   - 行 497 assist/repair edge：同 generate 处理。
   建议新增引擎内部 helper（放 ManifestJsonUtils.kt）：
   ```kotlin
   internal fun withPhaseMeta(manifestJson: String, activities: List<String>?): String
   ```
   `activities == null` 时原样返回。

宿主（scooper）：

7. `src/main/kotlin/scooper/service/PhaseSummaryFormatter.kt:42` `formatPhaseOutput`
   分派之前先读 `root["summary"]`（research/analyze 顶层）与 `root["_meta"]["activities"]`
   （generate/assist/repair）：命中则 join 展示；未命中走现有 per-phase 模板函数（内容不改）。

不动的：`ManifestResult`、`ToolActivity`、`StageInfo`、`DefaultScoopAiFactory`、
`ScoopAiBridge`、`CreateViewModel` 的回调链路（摘要随既有 `onPhaseOutput` 通道走，无新 API）。

### 为什么 generate/assist/repair 注入 `_meta` 而不是改 ManifestResult

`ManifestResult` 是跨进程 UI 数据类型，加 `activities` 意味着新回调或字段穿透 engine ->
factory -> bridge -> viewmodel 四层。而 `onPhaseOutput("generate", ...)` 已经把 manifest JSON
全文送到了宿主，注入 `_meta` 后宿主零 API 变更就能读到；版本错配时也只是退回模板句。
`_meta` 在宿主侧读完即弃，不进入 manifest 展示/保存路径（宿主只把它当摘要文本用）。
若担心宿主未来误把 phase output 当 manifest 存储，可在引擎侧于 normalize 之前剥离 `_meta`
（generate edge 先发摘要再传干净 manifest 给 normalize，现有 transformed 边已支持此分流，
见 ScoopManifestStrategy.kt:474-488 的 payload.manifest 转发结构）。

## prompt 改动文案草案

1. `PhaseSchemas.kt:11` RESEARCH_SCHEMA 的 JSON 示例加一行：
   `"summary": ["One short sentence per concrete action you performed, in order (e.g. \"Fetched repo info via GitHub API\", \"Read LICENSE to confirm GPL-3.0-or-later\", \"Picked stable v1.2.3 over nightly\"). 3-6 items."],`
   Rules 段加：
   `- "summary": list the concrete actions you actually performed this phase, one short sentence each, in order. Omit if nothing notable happened.`
2. `PhaseSchemas.kt:65` ANALYZE_SCHEMA 同样加 `summary` 示例行 + 同款 rule
   （示例句换成 "Computed SHA256 for x64 zip"、"Inspected archive layout, found app.exe at root"）。
3. `PhasePrompts.kt:579-594` generate 的 Finalize 段（第 4 步），在 explanation 说明之后追加：
   ```
   `activities` is an optional list of strings: 3-6 short sentences, each naming one concrete
   transformation you applied to the manifest (e.g. "Appended #/dl.7z for the NSIS installer",
   "Added autoupdate.hash from checksums.txt"). Same language as the user's input.
   It must never be placed inside `result`.
   ```
   CORRECT 示例同步补 `activities=["...","..."]`。
4. `PhasePrompts.kt:736/742` assist 段（explanation 指引旁）加一句：
   `When you modified the manifest, also pass optional activities: 3-6 short sentences describing each change you made and why.`
   repair（explanatory=false）轮不强制 activities（与 explanation 的非强制策略一致）。

文案要点：要求"每条一句、按顺序、只写实际做过的"，禁止营销词；给 2 个具体示例句，模型模仿成本低。

## 宿主消费改动

只改 `PhaseSummaryFormatter`：`formatPhaseOutput` 开头统一尝试读取模型摘要
（顶层 `summary` 数组或字符串、`_meta.activities`），命中且非空则 join 返回；
否则走现有 `formatResearchSummary` / `formatAnalyzeSummary` / `formatGenerateSummary` /
`formatRevisionSummary`，这些模板函数一行不改。

join 分隔符按 UI 渲染能力定：step 支持多行用 "\n"，仅单行用 "; "。

CreateViewModel / ScoopAiBridge 无改动（`onPhaseOutput` 通道原样携带新字段）。

## 兼容性矩阵

| 场景 | research/analyze | generate/assist/repair |
|------|------------------|------------------------|
| 新引擎 + 旧宿主 | 宿主手写 JsonObject 按键取值，未知键 `summary` 被忽略，显示旧模板句 | 顶层多出 `_meta` 键；`formatGenerateSummary`/`formatRevisionSummary` 只 containsKey 判断既有键，不受影响 |
| 旧引擎 + 新宿主 | `root["summary"]` 缺失 -> fallback 模板句 | 无 `_meta` -> fallback 模板句 |
| 旧 checkpoint/旧 run 工件 + 新引擎 | `phaseJson`（ScoopManifestStrategy.kt:55）有 `ignoreUnknownKeys` + `coerceInputValues`，无 summary 解码为 null；`CheckpointResume`/`ResumeData` 不受影响 | 同左 |
| 模型不填 summary/activities | 可选字段默认 null，策略层与宿主均判空 | `extractPayload` 容错为 null，宿主 fallback |
| 模型把 summary 填成字符串（research/analyze） | 引擎转发的是序列化后 JSON 透传（行 262/470），不做二次反序列化，不会抛异常；类型容错收敛在宿主（同时接受 array 与 string 形态） | `activities` 经 `extractPayload` 强约束（仅全 string 元素的 array 通过），字符串形态在此丢弃，不会进入转发 |

关键点：引擎对 research/analyze 的转发是序列化字符串透传，对 generate/assist 的注入
只操作模型原始 JSON，引擎侧 Kotlin 反序列化永远不会因模型乱填类型而失败；
全部类型容错收敛在 `extractPayload`（引擎）与 `summaryOf`（宿主）两处。

## 测试点（TDD，先红后绿）

引擎：

1. `core/src/commonTest/.../PhaseOutputTest.kt` 增补：
   - `ResearchOutput`/`AnalyzeOutput` 含 summary 的序列化 round-trip；
   - 旧 JSON（无 summary）解码 -> summary == null；
   - `"summary": "text"`（字符串形态）解码行为断言（documented behavior）。
2. `LenientStringFinishTool` 测试（jvmTest，参照 ReasoningRetryStubGatewayTest 的 stub 风格或直接测 extractPayload 逻辑）：
   - decodeArgs 接受 `{"result":"...","activities":["a","b"]}`；
   - activities 缺省/null/非数组/元素非字符串 -> null 或过滤；
   - wrapPayload 写出 activities；只传 result 的旧格式调用不破坏。
3. 策略层（`ScoopManifestGeneratorMockTest.kt` 风格，getMockExecutor mock finalize 调用）：
   - generate 分支 onPhaseOutput 收到的 JSON 含 `_meta.activities`；
   - activities 为 null 时不注入 `_meta`（原样透传）；
   - assist 分支同理；
   - normalize/verify 收到的 manifest 不含 `_meta`（摘要只进 onPhaseOutput 通道）。

宿主（scooper `PhaseSummaryFormatterTest.kt`）：

4. research 输出带顶层 `summary` 数组 -> 返回 join 后文本；
5. generate 输出带 `_meta.activities` -> 返回 join 后文本；
6. summary 为空数组/空串/null -> fallback 现有模板句（各 phase 一例回归）；
7. summary 为字符串形态 -> 正常显示（容错）；
8. 旧引擎输出（无任何新键）-> 与现有测试结果完全一致（回归）。

端到端（引擎验证流程三步）：跑 ripgrep 或 fd 生成，确认 research.json/analyze.json 出现
summary、宿主 steps 文案真实；.verify 安装测试后卸载清理；与官方 manifest 对比确认摘要
逻辑未影响 manifest 正确性。

## 实施顺序

1. 引擎：PhaseOutput/FinalizePayload 加字段 + LenientStringFinishTool 参数与容错提取
   （测试点 1-2 先行写红）。
2. 引擎：策略层四处转发 + `withPhaseMeta` helper（测试点 3 先行写红）。
3. 引擎：PhaseSchemas/PhasePrompts 文案。
4. 引擎全量测试 + 端到端跑包验证。
5. 宿主：PhaseSummaryFormatter 优先读模型摘要（测试点 4-8 先行写红）。
6. 宿主 UI 视需要调整多行渲染。
