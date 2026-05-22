# scoop-ai 架构评审报告

**评审日期**: 2026-05-20  
**评审范围**: `core`（业务逻辑与策略）、`tools`（工具实现）、`cli`（命令行入口）及测试

---

## 项目概述

**scoop-ai** 是一个 Kotlin JVM 项目，基于 Koog agent 框架构建，利用 LLM 三阶段工作流（Research → Analyze → Generate）自动生成 Scoop（Windows 包管理器）manifest。项目采用 Gradle 多模块结构：`core`（业务逻辑与策略）、`tools`（工具实现）、`cli`（命令行入口）。

整体上，项目的核心架构方向是正确的：  
- 采用 **三阶段 Agent 图策略**
- 在 `core / tools / cli` 间建立了清晰的依赖方向
- 使用接口抽象隔离具体实现，具备良好的可测试性和后续演进基础

当前主要架构风险集中在以下几个方面：
1. **核心生成器职责过重**
2. **工具阶段划分存在双重来源与字符串耦合**
3. **CLI 与配置解析职责混杂**
4. **Prompt / 校验 / HTTP 策略等横切关注点尚未独立成明确模块**

---

## 当前架构视图

```text
┌─────────────┐     ┌──────────────┐     ┌─────────────────────────────┐
│    cli      │────▶│   core       │────▶│   tools (implementations)   │
│  Main.kt    │     │ Generator    │     │ GitHubApi impl              │
│  参数解析    │     │ 图策略/三阶段  │     │ WebFetcher impl             │
│  环境变量    │     │ Prompt 构建    │     │ HashComputer impl           │
│  对象组装    │     │ JSON 提取/修复 │     │ ArchiveInspector impl       │
└─────────────┘     │ 验证修复       │     │ ManifestIo / Verifier       │
                    └──────────────┘     └─────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │   Koog Agents   │
                    │   AIAgent       │
                    │   ToolRegistry  │
                    │   LLM Providers │
                    └─────────────────┘
```

### 模块职责与依赖方向

- `core` 定义领域接口（`GitHubApi`、`WebFetcher`、`HashComputer`、`ArchiveInspector`、`ManifestIo`、`ScoopVerifier` 等），负责策略编排与核心流程
- `tools` 提供接口实现，依赖 `core`
- `cli` 负责参数解析、环境变量接入与对象图组装，依赖 `core + tools`

当前依赖方向总体合理：

- `core` → Koog 框架
- `tools` → `core` + Koog
- `cli` → `core` + `tools`

---

## 核心数据流

1. `ScoopManifestGenerator.generate(url)` 配置图策略（`scoopManifestStrategy`）
2. 图策略串联三个 `subgraphWithTask`：research → analyze → generate
3. 阶段间通过 `finalize_task_result` 返回结构化 JSON（`ResearchOutput` / `AnalyzeOutput`）
4. generate 阶段通过 `validate` / `write` 工具输出 manifest
5. `handleEvents` 在 `onToolCallCompleted` 中捕获 manifest JSON 作为后备
6. `validateAndFix()` 对生成结果做外部校验并在必要时自动修复

---

## 已经做得好的设计

| 方面 | 具体证据 |
|------|---------|
| **接口与实现分离** | `core` 中的工具接口为纯抽象，`tools` 提供具体实现，便于测试和替换 |
| **图策略显式编排** | `ScoopManifestStrategy.kt` 使用 Koog `strategy {}` + `subgraphWithTask` DSL，流程结构清晰 |
| **结构化阶段输出** | `ResearchOutput`、`AnalyzeOutput` 使用 `@Serializable` 数据类，避免阶段间自由文本传递 |
| **多 LLM Provider 支持** | `LlmProviderFactory` 统一适配多个 provider，并处理兼容性差异 |
| **可测试性投入** | 已有基于 mock executor 的流程测试与序列化边界测试 |
| **兼容性 workaround 集中隔离** | `RequestInterceptors.kt` 和 `ThinkingCompatibleStrategy.kt` 将外部兼容问题集中处理 |
| **非交互式安全** | `SafeAskUser` 在无控制台环境下返回默认值，避免 CI / 管道挂起 |

---

## 架构级问题清单

### 🔴 Must Fix

#### 1. `ScoopManifestGenerator` 职责过重，成为“万能生成器”

**位置**：`core/src/main/kotlin/com/scoopai/core/ScoopManifestGenerator.kt`（约 300+ 行）

**现状**：
该类同时承担了多类职责：

- 三阶段工具子集筛选
- Prompt 构建（research / analyze / generate / fix）
- LLM 参数构建
- 图策略配置
- Agent 创建与运行
- 事件处理与 manifest 捕获
- JSON 提取
- 验证与修复循环

**风险**：
- 单一类同时承载“编排 + Prompt + 解析 + 修复”，修改任一部分都需要理解整个文件
- 单元测试粒度过粗，不利于独立验证
- 后续新增阶段、切换 Prompt 组织方式、替换修复逻辑时，演进成本会快速上升

**建议**：
- 提取 **Prompt Builder**：
  - `ResearchPromptBuilder`
  - `AnalyzePromptBuilder`
  - `GeneratePromptBuilder`
  - `FixPromptBuilder`
- 提取 **验证修复模块**：
  - `ManifestValidator`
  - `ManifestFixer`
- 提取 **JSON 提取工具**：
  - `JsonExtractor`
- 让 `ScoopManifestGenerator` 尽量只保留：
  - 配置流程
  - 连接组件
  - 返回结果

**验证方式**：
- `ScoopManifestGenerator` 行数显著下降（例如控制在 ~100 行核心编排逻辑）
- Prompt、修复、JSON 提取分别拥有独立单元测试

---

#### 2. 工具阶段划分存在双重来源，且依赖字符串名称，缺乏类型安全

**位置**：
- `ScoopManifestGenerator.kt`
- `tools/DefaultToolRegistryFactory.kt`

**现状**：
- 一处按工具名字符串定义阶段工具子集
- 另一处在 `DefaultToolRegistryFactory` 中也按阶段组织工具
- 两者之间存在重复表达，且工具绑定依赖工具方法名的隐式约定

**风险**：
- 工具重命名后不会编译失败，只会在运行时“静默缺失”
- 新增工具时需要手动同步多个地方
- 阶段边界规则分散，不利于维护

**建议**：
优先选择以下两种方案之一：

1. **统一单一来源**
   - 阶段工具划分只保留一个定义入口
   - `ScoopManifestGenerator` 不再手写工具名集合

2. **引入显式常量/描述符**
   - 用 `ToolDescriptor` / 常量统一声明工具名
   - 阶段配置引用常量而非裸字符串

如果条件允许，更进一步可以将“阶段可用工具”建模为显式配置对象，而不是字符串过滤。

**验证方式**：
- 工具方法改名后应在编译期或测试期暴露问题，而不是运行时静默失效

---

#### 3. `Main.kt` 承担过多编排职责，CLI 入口缺少清晰的组合根边界

**位置**：`cli/src/main/kotlin/com/scoopai/cli/Main.kt`

**现状**：
`main()` 同时负责：

- 手工参数解析
- 环境变量解析
- provider 兼容性检查
- 外部进程探测（如 `scoop --version`）
- 对象图组装
- 运行与输出

**风险**：
- 参数解析缺乏类型安全
- 新增一个配置项往往要改多处逻辑
- `main()` 难以测试，也不适合作为长期扩展入口

**建议**：
- 引入轻量 CLI 库（如 `kotlinx.cli` 或 `picocli`）
- 提取 `CompositionRoot` / `AppFactory`
- 将配置解析分层：
  - CLI 参数
  - 环境变量
  - 默认值
- 最终形成统一的 `ConfigSource` / `AppConfigResolver`

**验证方式**：
- 新增一个命令行参数时，只需改一处配置声明或解析规则
- `main()` 只保留 `args -> config -> app.run()` 级别逻辑

---

### 🟡 Should Fix

#### 4. `runFixPhase` 的策略与主流程的 thinking 兼容处理不一致

**位置**：`ScoopManifestGenerator.kt` 中 `runFixPhase`

**现状**：
主流程使用的是为 thinking 模式做过兼容处理的策略，而 fix 阶段使用普通 `singleRunStrategy()`。

**风险**：
- 当使用支持/依赖 thinking 模式的 provider（如 DeepSeek 某些模式）时
- 修复阶段可能触发与主流程不同的行为，出现纯推理响应兼容问题
- 导致“主流程可用、修复阶段不稳定”的不一致现象

**建议**：
- 让 fix 阶段复用与主流程一致的 thinking 兼容策略
- 至少保证：
  - 请求参数处理一致
  - 响应重试策略一致
  - 工具调用行为一致

**验证方式**：
- 在 thinking 模式 provider 下构造“首轮生成失败、进入 fix”场景，确认修复阶段稳定可用

---

#### 5. `ProcessScoopVerifier` 手工拼接 JSON，存在格式与转义风险

**位置**：`tools/src/main/kotlin/com/scoopai/tools/verify/ProcessScoopVerifier.kt`

**现状**：
使用字符串拼接构造 JSON，而不是统一的 JSON builder。

**风险**：
- 遇到特殊字符时转义不完整
- 结果 JSON 可能非法
- 与项目其他部分的序列化风格不一致

**建议**：
- 使用 `kotlinx.serialization.json.buildJsonObject`
- 统一所有工具返回结构化 JSON 的构造方式

**验证方式**：
- 增加测试：输出中包含引号、反斜杠、控制字符时，反序列化仍然成功

---

#### 6. `ManifestIo.validate` 的 Scoop 语义校验深度不足

**位置**：`tools/src/main/kotlin/com/scoopai/tools/manifest/FileManifestIo.kt`

**现状**：
当前校验更偏基础结构校验，例如：

- `version` 必填
- `url` / `architecture` 存在
- URL 前缀合法
- hash 格式合法

但缺少更贴近 Scoop 规则的语义校验。

**风险**：
- manifest 可能“形式合法、语义仍错”
- LLM 会把很多本可早期拦截的问题拖到后续修复阶段
- 增加生成回合数和失败率

**建议**：
逐步增加以下校验：

- `bin` 字段结构合法性
- `architecture` 各架构块的 `url/hash` 完整性
- `extract_dir` 合法性
- `autoupdate` 模板合理性
- 与 Prompt 规则保持一致的反向校验

**验证方式**：
- 准备一组已知错误 manifest 样例，确保校验器能给出清晰错误信息

---

#### 7. Manifest 捕获逻辑脆弱，和工具实现细节耦合过深

**位置**：`ScoopManifestGenerator.kt` 中 `handleEvents`

**现状**：
当前通过监听特定工具名（如 `write` / `validate` / `read`）并解析工具参数来捕获 manifest JSON。

**风险**：
- 依赖工具名称硬编码
- 依赖工具参数结构，而非明确返回契约
- 容易捕获到中间草稿而非最终结果
- 工具方法重命名后逻辑可能悄然失效

**建议**：
优先级从高到低建议如下：

1. **只以最终持久化动作作为捕获依据**
   - 如只捕获 `write`
2. **从工具返回值中读取明确结果**
   - 不要依赖参数旁路解析
3. **更进一步，让 generate 阶段在 `finalize_task_result` 中直接返回最终 manifest**
   - 工具负责校验/写入，最终产物由阶段输出显式声明

**验证方式**：
- mock “先 validate 再 write”的场景，确认最终返回的是最终版而不是草稿

---

#### 8. 网络/IO 工具缺乏统一的超时、重试和错误策略

**位置**：
- GitHub / Web fetcher
- Hash 下载逻辑
- Archive 下载逻辑

**现状**：
不同工具各自处理 HTTP / IO，策略不统一，部分场景缺少显式超时和重试。

**风险**：
- 遇到 502、限流、网络抖动、慢下载时稳定性不足
- Agent 可能因为暂时性网络问题失败
- 同类问题在不同工具上的行为不一致，调试困难

**建议**：
- 引入统一的 HTTP / IO 策略层，例如：
  - `RetryingHttpClient`
  - 统一 timeout 配置
  - 对 5xx / IOException 做指数退避
- 抽出公共下载逻辑，避免每个工具重复实现自己的下载策略

**验证方式**：
- 用 mock server 模拟短暂失败后恢复成功的场景，验证整体流程可恢复

---

#### 9. Provider 配置与 API Key 解析职责分散

**位置**：
- `ScoopManifestConfig`
- `Main.kt`
- `LlmProviderFactory`

**现状**：
provider 到 API key / base-url / 兼容性规则的解析逻辑分散在多个地方。

**风险**：
- 新增 provider 需要修改多处 `when`
- 数据类承载了解析职责，边界不清
- CLI、环境变量、工厂三者可能演化不同步

**建议**：
- 引入独立的 `ApiKeyResolver` / `ProviderConfigResolver`
- `ScoopManifestConfig` 保持为纯数据对象
- 将 provider 相关规则集中到统一模块管理

**验证方式**：
- 新增 provider 时只需在单一位置补充映射和规则

---

#### 10. Prompt 模板与代码强耦合，维护成本偏高

**位置**：`SystemPrompts.kt` 及相关 Prompt 拼接逻辑

**现状**：
多段长 Prompt 直接硬编码在 Kotlin 代码中，并与示例、schema、说明文本混合组织。

**风险**：
- Prompt 调整必须重新编译
- 规则定位困难
- 非开发者很难参与 Prompt 调优
- 代码文件承担了过多“内容资产”

**建议**：
- 将 Prompt 模板迁移到 `resources/prompts/`
- 代码中只做变量插值与加载
- 保持 Prompt 内容与逻辑代码解耦

**验证方式**：
- Prompt 内容迁移后，现有测试仍通过
- 修改 Prompt 无需改动业务逻辑类

---

### 🟢 Optional

#### 11. 类命名与实际实现不一致

**位置**：
- `KtorGitHubApi.kt`
- `KtorWebFetcher.kt`

**现状**：
类名带 `Ktor`，实际实现基于 `java.net.http.HttpClient`。

**风险**：
- 命名误导维护者
- 阅读成本增加

**建议**：
- 重命名为更贴近实现的名称，例如 `JdkHttpGitHubApi` / `JdkHttpWebFetcher`

---

#### 12. 缺少项目级 README 与正式架构文档入口

**现状**：
根目录缺少面向新贡献者的项目级说明。

**风险**：
- onboarding 成本偏高
- 外部读者难以快速理解：
  - 项目目标
  - 构建方式
  - 运行方式
  - 架构边界

**建议**：
新增 `README.md`，至少包含：

- 项目简介
- 构建命令
- 运行命令
- 环境变量说明
- 三阶段架构概览
- 测试命令

---

#### 13. 若 `bucket/` 为生成产物目录，建议补充自动验证机制

**说明**：
这一点取决于 `bucket/` 的定位是否是“持续维护的产物集合”。

**风险**：
如果其中存放的是实际产出的 manifest，缺少自动校验会导致：
- URL 失效
- hash 过期
- 版本落后

**建议**：
- 若该目录属于正式输出资产，可考虑增加 CI 校验任务

---

## 渐进式演进建议

### 短期（1–2 个迭代，低风险）

| 优先级 | 改进项 | 文件/模块 | 预期收益 |
|--------|--------|-----------|---------|
| 1 | `ProcessScoopVerifier` 改为 `buildJsonObject` | `ProcessScoopVerifier.kt` | 消除 JSON 拼接风险 |
| 2 | 统一工具阶段划分来源，消除裸字符串重复配置 | `ScoopManifestGenerator.kt` / `DefaultToolRegistryFactory.kt` | 降低运行时静默失败风险 |
| 3 | fix 阶段复用 thinking 兼容策略 | `ScoopManifestGenerator.kt` | 提升 provider 行为一致性 |
| 4 | 提取 Prompt Builder | 新建 `prompt/` 包 | 降低生成器复杂度 |
| 5 | 添加根目录 `README.md` | 根目录 | 降低 onboarding 成本 |

### 中期（2–4 个迭代，中等风险）

| 优先级 | 改进项 | 文件/模块 | 预期收益 |
|--------|--------|-----------|---------|
| 6 | 提取 `ManifestValidator` / `ManifestFixer` | 新建 `validation/` 包 | 校验与修复独立演进 |
| 7 | 引入统一 HTTP 重试/超时层 | 新建 `http/` 包或公共模块 | 提升网络鲁棒性 |
| 8 | 扩展 `ManifestIo.validate` 语义校验 | `FileManifestIo.kt` | 更早发现生成错误 |
| 9 | 提取 `ApiKeyResolver` / `ProviderConfigResolver` | `core/config` 或 `core/llm` | 统一 provider 配置职责 |
| 10 | Prompt 外部化到 resources | `resources/prompts/` | 提升可维护性与调优效率 |

### 长期（可选方向）

| 方向 | 说明 |
|------|------|
| **统一 HTTP 抽象** | 减少多套网络实现带来的维护分裂 |
| **可插拔 archive 支持** | 为 zip 之外的格式预留扩展点 |
| **增量更新模式** | 在已有 manifest 基础上做最小变更 |
| **批量生成** | 支持多 URL 输入并复用连接与线程资源 |
| **Provider 注册表化** | 降低新增 provider 的修改面 |

---

## 总结

`scoop-ai` 的底层架构方向是健康的：  
**三阶段图策略、接口与实现分离、结构化阶段输出、兼容性问题集中隔离** 这些关键决策都值得保留。

当前最值得优先处理的两个问题是：

1. **`ScoopManifestGenerator` 职责过重**
2. **工具阶段划分的双重来源与字符串耦合**

它们直接影响系统的：
- 可维护性
- 演进速度
- 正确性保障

在此基础上，再逐步推进：
- fix 阶段策略一致性
- 校验器增强
- HTTP 统一策略
- Prompt 外部化
- Provider 配置职责收敛

这样可以在不推翻现有设计的前提下，持续把当前架构演进成一个更稳定、可维护、可扩展的生成系统。
