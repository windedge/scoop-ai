# 计划：scooper 集成 scoop-ai - 库化与对接

将 scoop-ai 作为 **库** 集成进 Scooper 桌面应用，提供对话式 Create 能力（从 GitHub URL 生成 Scoop manifest）。

- 本文档位于 scoop-ai 仓库，面向 **本仓库需完成的改动** 与集成契约。
- Scooper 侧完整执行计划（产品 / UI / 安全层）：  
  `C:\Seafile\Works\IdeaProjects\scooper\docs\create-manifest-ai-plan.md`
- Scooper 旧规则方案（保留参考，非现行主路径）：  
  `C:\Seafile\Works\IdeaProjects\scooper\docs\create-manifest-plan.md`

两份现行计划内容应对齐；若冲突，以双方已确认决策表为准。

---

## 1. 背景与目标

### 背景

- scoop-ai 已具备 research / analyze / generate 三阶段 agent、工具链与 CLI。
- Scooper 需要 Create 功能：用户粘贴 GitHub URL，生成可用 Scoop manifest。
- 决策：**库依赖集成**（非 CLI 外包），**跳过规则 MVP**，AI 生成为主路径。

### 目标（scoop-ai 侧）

| 目标 | 说明 |
|------|------|
| 可被依赖 | `core` + `tools` 可 `publishToMavenLocal`，坐标稳定 |
| 可被装配 | 提供与 CLI 解耦的 `DefaultScoopAiFactory` |
| 可非交互 | AskUser 在 nonInteractive 下自决，不阻塞 GUI |
| 可配置 | provider/key/model/baseUrl 由调用方注入，不强制读 CLI env |
| 可演进 | 二期支持可注入 AskUser 回调与 refine 对话 |

### 非目标 (MVP)

- 不改造 Scooper UI（在 scooper 仓库完成）
- 不强制 agent 内置 verify 作为唯一测试安装路径（scooper 自有安全层）
- 不统一 Kotlin 到 2.3（先共存）
- 不在本仓库实现 Compose 弹窗

---

## 2. 已锁定决策表

| # | 议题 | 决策 | 备注 |
|---|------|------|------|
| 1 | 依赖方式 | `publishToMavenLocal` 发布 `core` + `tools` | scooper 通过 `mavenLocal()` 引用 |
| 2 | Kotlin 版本 | 先共存：scoop-ai 2.1.x / scooper 2.3.x | `-Xskip-metadata-version-check` |
| 3 | AskUser (MVP) | `nonInteractive` 自决 | 默认答复继续 agent |
| 4 | 库入口 | `DefaultScoopAiFactory` 下沉到 `:tools` | CLI `CompositionRoot` 委托之 |
| 5 | Test install 包名 | scooper 强制 `*-scooper-test` | 本仓库可选支持 suffix |
| 6 | Provider (MVP) | `openai-compatible` + `deepseek` | 其它 provider 代码可保留，UI 不暴露 |
| 7 | Settings | scooper 独立 Settings.AI 页 | 配置注入 factory |
| 8 | 文档 | 两边各一份交叉引用 | 旧 create-manifest-plan 保留 |
| 9 | 内存 | `-Xmx512m` | CLI / 文档约定 |
| 10 | 二期 | AskUser 弹窗 + 对话 refine | 需可注入 AskUser tool |

### 产品形态补充决策

| 议题 | 决策 |
|------|------|
| 集成形态 | **库依赖**，不要 CLI 外包 |
| UI 形态 | scooper **对话式 Create UI**，不是 5 步向导 |
| 主路径 | **跳过规则 MVP**，AI 生成做主路径 |
| 任务调度 | scooper Create **不走 TaskQueue** |
| 测试安装 | scooper **不用 `installVersion`**，专用安全接口 |

---

## 3. 产品形态

### 3.1 MVP（集成视角）

```
Scooper Create UI
      |
      | ScoopAiConfig (from Settings.AI)
      v
DefaultScoopAiFactory (:tools)
      |
      +--> LlmProviderFactory (deepseek | openai-compatible)
      +--> DefaultToolRegistryFactory
      +--> SafeAskUser (nonInteractive)
      +--> ScoopManifestGenerator (:core)
      |
      v
ManifestResult { manifest, suggestedAppName, stages, tokenUsage }
      |
      v
Scooper: edit / test install (*-scooper-test) / copy / save
```

### 3.2 二期

| 能力 | scoop-ai 侧 | scooper 侧 |
|------|-------------|------------|
| AskUser 弹窗 | 支持注入自定义 AskUser / 回调 tool | Compose 对话框 |
| 对话 refine | 暴露 refine API 或 userPrompt 续跑 | 聊天追加指令 |
| 更多 provider | 已有 factory 路径 | Settings 扩展 |
| resume / artifacts | 已有能力接线 | UI 可选开启 |

---

## 4. 模块边界与架构图

### 4.1 仓库边界

```
+---------------- scoop-ai ------------------+     +------------- scooper --------------+
|                                            |     |                                    |
|  :core                                     |     |  CreateScreen / CreateViewModel    |
|    ScoopManifestGenerator                  |     |           |                        |
|    strategy / prompts / types              |     |           v                        |
|                                            |     |  CreateService / ScoopAiBridge      |
|  :tools                                    |     |           |                        |
|    DefaultScoopAiFactory  <----------------+-----+--- mavenLocal dependency           |
|    DefaultToolRegistryFactory              |     |           |                        |
|    LlmProviderFactory                      |     |           v                        |
|    SafeAskUser / FileManifestIo / ...      |     |  testInstall (*-scooper-test)      |
|                                            |     |  Settings.AI                        |
|  :cli                                      |     |  (NOT TaskQueue, NOT installVersion)|
|    CompositionRoot -> factory              |     |                                    |
|    GenerateCommand                         |     +------------------------------------+
+--------------------------------------------+
```

### 4.2 职责划分

| 层 | 归属 | 职责 |
|----|------|------|
| Agent 编排 | `:core` | 三阶段 + verify/refine |
| 工具与装配 | `:tools` | 网络 / 文件 / hash / factory |
| CLI | `:cli` | 命令行 UX；委托 factory |
| UI / 安全测试安装 | scooper | 对话 UI、临时名、确认框、清理 |
| 配置持久化 | scooper Settings.AI | provider/key 等 |

### 4.3 不应泄漏的边界

- `:tools` / `:core` **不得** 依赖 Compose、scooper 包、Clikt（factory 本身不依赖 Clikt）。
- CLI 特有逻辑（EnvArgs 解析、Clikt echo）留在 `:cli`。
- scooper **不得** 把 Create 生成任务塞进 TaskQueue。
- scooper test install **不得** 调用会卸载正式包的 `installVersion`。

---

## 5. scoop-ai 侧改动清单

### 5.1 发布 `:core` + `:tools` 到 Maven Local

| 项 | 内容 |
|----|------|
| 现状 | `:core` 已有 `maven-publish`（`com.scoopai:scoop-ai-core`）；`:tools` **未** 配置 publishing |
| 改动 | `:tools` 增加 `maven-publish`，artifactId `scoop-ai-tools`，group `com.scoopai`，version = root `0.1.0`（或当前） |
| 依赖 | 建议 `api(project(":core"))` 使消费者传递获得 core；否则文档要求 scooper 同时声明两坐标 |
| 命令 | `gradlew.bat :core:publishToMavenLocal :tools:publishToMavenLocal` |
| 验收 | `~/.m2/repository/com/scoopai/scoop-ai-core/` 与 `scoop-ai-tools/` 有对应版本 |

### 5.2 下沉 `DefaultScoopAiFactory`

| 项 | 内容 |
|----|------|
| 位置 | `tools/src/jvmMain/kotlin/com/scoopai/tools/DefaultScoopAiFactory.kt`（或 `factory/` 子包） |
| 来源 | 从 `cli/.../CompositionRoot.kt` 抽取装配逻辑 |
| CLI | `CompositionRoot.createGenerator()` 改为委托 factory，避免双份逻辑 |

建议 API：

```kotlin
data class ScoopAiConfig(
    val provider: String,              // "deepseek" | "openai-compatible"
    val apiKey: String,
    val baseUrl: String? = null,
    val model: String? = null,
    val githubToken: String? = null,
    val appName: String? = null,
    val nonInteractive: Boolean = true,
    val verify: Boolean = false,
    val maxIterations: Int = 500,
    val keepCache: Boolean = false,
    val persistMode: Boolean = false,  // 若 FileManifestIo 需要
)

class DefaultScoopAiFactory {
    fun create(
        config: ScoopAiConfig,
        onPhase: ((String) -> Unit)? = null,
        artifacts: com.scoopai.core.artifacts.SensitiveArtifactWriter? = null,
        usedAppNamesProvider: (suspend () -> Set<String>)? = null,
    ): ScoopManifestGenerator
}
```

实现要点：

1. 用 `ProviderConfigResolver` / `LlmProviderFactory` 建 executor + model（支持显式参数，不只读 env）。
2. 用 `DefaultToolRegistryFactory.create(...)` 建 tools。
3. `nonInteractive=true` 时确保 AskUser 不阻塞（见 5.3）。
4. `verify=false` 时不注册 / 不启用 `ProcessScoopVerifier`（scooper UI 自测）。
5. 返回的 generator 与 CLI 行为一致（便于 mock 测试复用）。

### 5.3 AskUser nonInteractive 契约

| 项 | 内容 |
|----|------|
| 现状 | `SafeAskUser` 读取 `SCOOP_AI_NON_INTERACTIVE` 系统属性 / 环境变量 |
| 问题 | GUI 进程若未设属性，可能尝试读 stdin 或返回默认；行为需 **明确可配** |
| MVP 方案 A | factory 在 create 时 `System.setProperty("SCOOP_AI_NON_INTERACTIVE", "true")`（简单，有全局副作用） |
| MVP 方案 B（推荐） | 抽取 `AskUser` 策略接口 / 可替换 tool；factory 注册 `NonInteractiveAskUser` |
| 二期 | 注入 `suspend (String) -> String` 回调 tool，对接 scooper 弹窗 |

验收：在无 console、无 stdin 的环境中，`generate()` 不永久阻塞。

### 5.4 Provider 范围

| Provider | MVP | 说明 |
|----------|-----|------|
| deepseek | 是 | |
| openai-compatible | 是 | 需要 baseUrl |
| openrouter / anthropic / ollama | 代码可保留 | scooper UI 不暴露 |

`ProviderConfigResolver.resolve(...)` 应继续支持 **纯参数** 调用（不强制 System.getenv），便于桌面注入。

### 5.5 Test install / 临时名

| 项 | 内容 |
|----|------|
| 主责 | **scooper** 在 UI 测试安装前改写为 `<app>-scooper-test` |
| 本仓库 | MVP 默认 `verify=false`，避免 agent 内置 verify 与正式名冲突 |
| 可选 | `ProcessScoopVerifier` / `FileManifestIo` 支持 test suffix（非阻塞） |

### 5.6 内存与资源

| 项 | 内容 |
|----|------|
| JVM | CLI 与文档约定 `-Xmx512m` |
| 缓存 | `ScoopCacheManager`；`keepCache=false` 时清理 |
| 临时文件 | 现有 try/finally 纪律保持 |

### 5.7 Kotlin 共存

| 项 | 内容 |
|----|------|
| 本仓库 | Kotlin 2.1.21 + 已有 `-Xskip-metadata-version-check`（兼容 Koog） |
| scooper | 2.3.x，消费 jar 时同样 skip metadata check |
| 后续 | 评估升到与 scooper 同一主版本（非 MVP） |

### 5.8 测试

| 测试 | 说明 |
|------|------|
| Factory 单测 | 配置映射、缺 baseUrl 报错、nonInteractive 工具注册 |
| CLI 回归 | `CompositionRoot` 委托后 Generate 路径仍通 |
| 现有 mock | `ScoopManifestGeneratorMockTest` 等保持绿 |
| 手动 | `publishToMavenLocal` 后 scooper 能编译引用 |

### 5.9 文档

- 保持本文与 scooper `docs/create-manifest-ai-plan.md` 决策表一致。
- README / providers 文档可补充 "library usage" 小节（可选，非阻塞）。

---

## 6. scooper 侧改动清单（摘要）

> 详情见 scooper 仓库 `docs/create-manifest-ai-plan.md` 第 6 节。

| 区域 | 要点 |
|------|------|
| Gradle | `mavenLocal()` + `scoop-ai-tools` (+ core) + skip metadata |
| 路由 | `AppRoute.Create`、侧边栏、快捷键 |
| Settings | `Settings.AI`：provider / key / baseUrl / model / github token |
| Create UI | 对话式，非 5 步向导 |
| Bridge | Settings -> `ScoopAiConfig` -> factory.generate |
| Test install | 强制 `*-scooper-test`；ProcessBuilder；确认框；清理 |
| 禁忌 | 不用 TaskQueue；不用 `installVersion` |

---

## 7. 分阶段落地与验收标准

### Phase A - 库化（本仓库主责）

| 步骤 | 工作 | 验收 |
|------|------|------|
| A1 | `:tools` maven-publish | `publishToMavenLocal` 成功 |
| A2 | `DefaultScoopAiFactory` + `ScoopAiConfig` | 单测 + CLI 委托 |
| A3 | nonInteractive 可配置 | 无控制台不挂起 |
| A4 | 文档交叉引用 | 两边决策表一致 |

**门禁**：scooper 能依赖本地坐标并编译。

### Phase B/C/D - scooper 集成（scooper 主责）

见 scooper 计划：壳与配置 -> 生成主路径 -> test install 与导出。

本仓库配合：

- 版本 bump 后重新 `publishToMavenLocal`
- 修复集成中发现的 API 缺口（显式 config、onPhase 等）

### Phase E - 打磨

- 取消生成时的资源释放（cache / coroutine）
- 日志脱敏复查
- 回归 CLI + mock 测试

### 二期 Phase F+

1. 可注入 AskUser 回调
2. refine API 对桌面友好
3. 更多 provider 文档化
4. Kotlin 版本统一评估

---

## 8. 风险与约束

### 8.1 继承 scooper 旧计划的安全约束（集成方必须遵守）

| 约束 | 原因 |
|------|------|
| 禁止用会卸载正式包的 install 路径做测试 | 损坏用户环境 |
| Create 不进全局 Scoop 任务队列 | 阻塞其它安装/更新 |
| 临时文件必清理 | 磁盘泄漏 |
| Save to Bucket 路径校验 | 路径穿越 |

### 8.2 本仓库相关风险

| 风险 | 缓解 |
|------|------|
| tools 未 publish 导致无法依赖 | Phase A1 优先 |
| CompositionRoot 与 factory 双份逻辑漂移 | CLI 强制委托 factory |
| AskUser 在 GUI 挂起 | nonInteractive 默认 true + 测试 |
| 全局 System property 副作用 | 优先可替换 tool，而非只 setProperty |
| Kotlin metadata 不匹配 | skip-metadata-version-check + e2e |
| 依赖体积 / exclude 不一致 | 保持 bedrock/opentelemetry 等 exclude；文档说明 |
| agent verify 与 UI test 双重安装 | MVP `verify=false` |
| API key 进日志 | 现有 sanitize；工厂层不 log key |

### 8.3 Test Install 安全 Checklist（scooper 实现；本仓库知悉）

- [ ] 安装名 = `<base>-scooper-test`
- [ ] 临时 json 用完删除
- [ ] `scoop install <file> -s`
- [ ] 不进 TaskQueue
- [ ] 仅 local
- [ ] 确认对话框 + 脚本警告
- [ ] 结束后 uninstall + cache rm
- [ ] 超时保护
- [ ] 不影响正式同名 app

### 8.4 开发工作流

```text
# scoop-ai
cd C:/Seafile/Works/kotlin/scoop-ai
gradlew.bat :core:publishToMavenLocal :tools:publishToMavenLocal

# scooper
cd C:/Seafile/Works/IdeaProjects/scooper
gradlew.bat compileKotlin
# 再跑 Create 功能验证
```

不自动 git commit。

---

## 9. 与 create-manifest-plan.md 的关系

| 维度 | 旧 scooper 规则计划 | 本集成计划 |
|------|---------------------|------------|
| 主路径 | 规则解析/解压/生成 | scoop-ai agent |
| 集成 | 无 | 库依赖 + factory |
| 状态 | scooper 仓库保留参考 | **现行** 两边执行计划 |
| 仍有效 | 测试安装安全、TaskQueue 禁忌等 | 纳入第 8 节 |

规则路径不作为 MVP；若未来需要离线 fallback，另开计划。

---

## 10. 关键文件索引（本仓库）

| 路径 | 动作 |
|------|------|
| `tools/build.gradle.kts` | 加 `maven-publish`；评估 `api(project(":core"))` |
| `core/build.gradle.kts` | 已有 publish；确认 artifact 坐标 |
| `tools/.../DefaultScoopAiFactory.kt` | **新建** |
| `tools/.../interaction/SafeAskUser.kt` | nonInteractive 可配置 / 可替换 |
| `tools/.../config/ProviderConfigResolver.kt` | 确保纯参数 resolve 可用 |
| `cli/.../CompositionRoot.kt` | 委托 factory |
| `docs/scooper-integration-ai-plan.md` | 本文 |
| scooper `docs/create-manifest-ai-plan.md` | 对端完整计划 |

---

## 11. 成功标准（MVP Done - 本仓库视角）

1. `publishToMavenLocal` 产出 `com.scoopai:scoop-ai-core` 与 `com.scoopai:scoop-ai-tools`。
2. `DefaultScoopAiFactory` 可在无 CLI 环境下创建 `ScoopManifestGenerator` 并完成 generate（mock 或真实）。
3. `nonInteractive=true` 时 AskUser 不阻塞。
4. CLI 经 factory 委托后回归通过。
5. scooper 可编译依赖本地坐标；两边文档交叉引用齐全。
