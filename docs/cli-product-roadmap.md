# 计划

把 scoop-ai CLI 从内部工具产品化为可发版工具，分 4 个阶段共 39 个任务，每个任务对应一次提交 + 一个 TDD 循环。阶段与版本号对齐（Phase N = v0.N.0，Phase 4 仅在触发条件满足时发布）。核心是让失败可定位、结构可演化、长期使用顺畅。

## 范围

- 包含：
  - **Phase 1 (v0.1.0)**：退出码 + 阶段进度 + 版本 + 脱敏（9 任务）
  - **Phase 2 (v0.2.0)**：clikt 子命令 + CompositionRoot + CLI 测试（14 任务）
  - **Phase 3 (v0.3.0)**：self-bootstrap + JSON + RunArtifacts + 文档（12 任务）
  - **Phase 4 (v0.4.0，可选)**：配置文件 + 密钥环（4 任务，按需触发）
- 不包含：
  - OTLP 导出、GraalVM 原生镜像、brew/msi、初始化向导、i18n、非 Scoop 包管理器
  - checkpoint 恢复（仅 T-3.05b spike 调研，结论可能 DEFER，不阻塞 v0.3.0）
  - 中文 README（英文优先生态，非发版阻塞项）

## 关键文件

- `cli/.../Main.kt` - 293 行，Phase 2 拆分为 ScoopAi.kt + CliRunner.kt，Phase 1 加顶层 try/catch
- `core/.../agent/ScoopManifestStrategy.kt` - 阶段切分点 research(L97)/analyze(L110)/generate(L126)/refine(L135)，Phase 1 加进度日志
- `core/.../Types.kt:15-22` - `ManifestResult` 5 字段无 `@Serializable`，Phase 3 加注解 + `stages`
- `gradle.properties` - 无 `version` 字段，T-1.01 添加为单一版本源
- `cli/build.gradle.kts:8` - 硬编码 `0.1.0`，T-1.01 改为 `rootProject.version`
- `cli/src/main/resources/logback.xml` - 14 行，T-1.06 收敛 koog 噪音
- `gradle/libs.versions.toml` - 无 clikt，T-2.01 添加

## 可复用代码

- `release` / `release-note` skill - 用于 T-3.10/T-3.11/T-4.04 切版与发版说明
- `LlmProviderFactory`（`core/.../llm/`）- DeepSeek/openai-compatible 双重编码 workaround，providers 文档引用
- `getMockExecutor`（Koog `agents-test`）- 第 3 层 mock 集成测试的执行器
- `SensitiveMasker`（T-1.04 新建）- 纯函数，被 T-2.02/T-3.04/T-3.05a/b 共享复用

## 输出契约（唯一事实标准）

- **默认模式**：stdout = 仅最终结果（`Written to <path>`、token 摘要）；stderr = 进度/警告/错误/`[phase]` 行
- **JSON 模式**（`--output-format json`）：stdout = 一个完整 JSON 对象 + 换行，可 `jq` 解析；覆盖层关闭
- **进度覆盖层**：`\r[phase] <name>`，仅 `System.console() != null` 且非 `--no-progress` 且非 JSON 时启用
- **脱敏**：API 密钥 / GitHub token / Authorization / LLM 请求体在所有 stderr（含 debug）必须脱敏；`--debug` 加堆栈但不放宽脱敏
- **退出码**（项目自定义，非 sysexits.h）：`SUCCESS(0) GENERAL(1) USAGE(2) CONFIG(3) NO_ASSETS(64) LLM_ERROR(65) VERIFY_FAILED(66) IO_ERROR(70)`，每个需在 2+ 种 shell 验证

**标志表**（CLI 标志覆盖环境变量）：`-o/--output`, `-P/--provider`, `-m/--model`, `--base-url`, `--api-key`, `--github-token`, `--app-name`, `--no-cache`(T-3.06 弃用), `-V/--verify`, `-p/--prompt`, `-n/--non-interactive`, `-s/--persist`, `--output-format`, `--debug`, `--no-progress`, `-h/--version`。

**敏感环境变量**（必须脱敏）：`SCOOP_AI_API_KEY`、`SCOOP_AI_DEBUG_HTTP`、`DEEPSEEK/OPENAI/ANTHROPIC/OPENROUTER_API_KEY`、`GITHUB_TOKEN`/`GH_TOKEN`。非敏感：`SCOOP_AI_PROVIDER/BASE_URL/MODEL/OUTPUT/VERIFY/DEBUG`。

**测试 4 层**（"N 个测试"永不作为验收）：第 1 层纯单元 → 第 2 层 clikt 命令测试 → 第 3 层 mock 集成 → 第 4 层手动发版（真实 LLM，跑 ripgrep/fd + `scoop install .verify/*.json` + 对比官方 bucket）。

## 实施步骤

### Phase 1 - 硬性修复 (v0.1.0，发版阻塞项)

1. **T-1.01 版本集中管理**
   - 文件：`gradle.properties`、`cli|core|tools/build.gradle.kts`
   - 变更：加 `version=0.1.0`，硬编码版本替换为 `rootProject.version`
   - 验收：无硬编码版本；shadowJar 输出 `cli-0.1.0-all.jar`
2. **T-1.02 ExitCode 枚举**
   - 文件：`cli/.../error/ExitCode.kt` + 测试
   - 变更：8 个退出码枚举（见契约）
   - 验收：8 码唯一；`SUCCESS==0`、`USAGE==2`、`NO_ASSETS != USAGE`
3. **T-1.03 CliError 密封类**
   - 文件：`cli/.../error/CliError.kt` + 测试（依赖 T-1.02）
   - 变更：7 个子类，一对一映射非 SUCCESS 退出码
   - 验收：每个子类有预期退出码；`cause`/`msg` 往返；sealed `when` 编译通过
4. **T-1.04 Main try/catch + SensitiveMasker**
   - 文件：`cli/.../Main.kt`、新建 `error/SensitiveMasker.kt` + 测试（依赖 T-1.03）
   - 变更：用 try/catch(CliError)/catch(Exception)/finally 包裹 `main()`；7 处 `println+return` 改抛 CliError；`exitProcess(e.exit.code)`；默认错误 `ERROR: <Type>: <msg>` 到 stderr 无堆栈；脱敏所有敏感环境变量/Authorization/token
   - 验收：无早期返回；缺参/无效 persist 退出码为 USAGE/CONFIG；默认无堆栈无原文密钥；`--debug` 加堆栈且仍脱敏
5. **T-1.05 阶段进度日志**
   - 文件：`core/.../ScoopManifestGenerator.kt`、`agent/ScoopManifestStrategy.kt` + 测试
   - 变更：4 处 subgraph 位置（L97/110/126/135）经 KotlinLogging 向 stderr 输出 `[phase:<name>] starting` 和 `completed in <s>, tokens=<n>`，无条件输出，不引入 ProgressSink（推迟 T-3.04）
   - 验收：stderr 恰好 8 行 `[phase]`，顺序 research/analyze/generate/refine，永不出现在 stdout
6. **T-1.06 收敛 logback**
   - 文件：`cli/src/main/resources/logback.xml`
   - 变更：`ai.koog.agents=WARN`、`ai.koog.prompt=WARN`、`ai.koog.prompt.executor=ERROR`，保留 `com.scoopai=INFO`
   - 验收：无 koog INFO/DEBUG 噪音；自有 `[phase]/[tool]/[cache]` 保留
7. **T-1.07 CLI 进度覆盖层**
   - 文件：新建 `cli/.../ConsoleProgress.kt`、`Main.kt` + 测试（依赖 T-1.05）
   - 变更：stderr 打印 `\r[phase] <name>`，完成后 `\n`，按契约激活，与 T-1.05 行日志独立
   - 验收：TTY 显示覆盖层；`--no-progress` 禁止；非 TTY 无 `\r`；JSON 模式关闭
8. **T-1.08 --version 标志**
   - 文件：`Main.kt`、新建 `Version.kt` + 测试（依赖 T-1.01）
   - 变更：`object Version` 暴露 `VERSION`（BuildConfig 读 `gradle.properties`）+ 提交哈希（`git rev-parse --short HEAD`，失败回退 `unknown`）；`--version` 最高优先级打到 stdout
   - 验收：打印 `scoop-ai 0.1.0 (commit xxxxxxx)`，exit 0；git 外可用；覆盖其他标志
9. **T-1.09 CHANGELOG.md**
   - 文件：新建 `CHANGELOG.md`（依赖 T-1.01/02/05/08）
   - 变更：Keep a Changelog 1.1；v0.1.0 `Added` 列三阶段生成/缓存/验证/退出码/版本/进度/脱敏等；`Known Limitations` 只列推迟项
   - 验收：纯 ASCII；Added 匹配 Phase 1 可交付物；Known Limitations 排除 v0.1.0 已含内容

### Phase 2 - 可演化结构 (v0.2.0，可降级 T-2.07/12)

10. **T-2.01 添加 clikt**
    - 文件：`gradle/libs.versions.toml`、`cli/build.gradle.kts`
    - 变更：引入 clikt 4.x
    - 验收：编译通过；先 spike 验证无 coroutines/serialization 传递冲突
11. **T-2.02 拆分 Main.kt**（依赖 T-2.01）
    - 文件：新建 `ScoopAi.kt`（`NoOpCliktCommand`，处理 --version/--help）、`CliRunner.kt`，精简 `Main.kt`
    - 变更：Main 顶部禁用 clikt ANSI 防 Windows cmd 乱码
    - 验收：Main.kt < 10 行；clikt 风格 `--help`；14 标志可解析
12. **T-2.03 CompositionRoot**（依赖 T-2.02）
    - 文件：新建 `CompositionRoot.kt`、`EnvArgs.kt` + 测试
    - 变更：`by lazy` 持有所有协作者；`fromEnv(EnvArgs)` 工厂；`EnvArgs` 为 data class（不直接访问 `System.getenv`）；不用 Koin/Dagger
    - 验收：Main.kt 不再 `new` 6 个具体类；无反射即可构造 2 个 EnvArgs
13. **T-2.04 GenerateCommand**（依赖 T-2.02/03）
    - 文件：新建 `command/GenerateCommand.kt`
    - 变更：clikt `option()`/`flag()` 实现 14 标志；`argument("url")`；调 `CompositionRoot.fromEnv().generate()`
    - 验收：`scoop-ai <url>` 和 `scoop-ai generate <url>` 均成功；错误标志 exit 2
14. **T-2.05 DoctorCommand**（依赖 T-2.04）
    - 文件：新建 `DoctorCommand.kt`、`diagnostic/HealthCheck.kt` + 测试
    - 变更：7 项检查（jdk/os/scoop/api-key 脱敏/cache-dir/write-permission/version）；WARN exit 0、FAIL exit 1
    - 验收：固定顺序 7 行表格；api-key 脱敏不泄露长度；覆盖全通过/含警告/含失败三场景
15. **T-2.06 ConfigShowCommand**（依赖 T-2.04）
    - 文件：新建 `ConfigShowCommand.kt`
    - 变更：调 `ProviderConfigResolver.resolve()`；打印 provider/model/baseUrl/hasApiKey/persistMode/verify/cache；apiKey 仅显示 set/unset；显示运行时配置非持久化
    - 验收：缺时 `api-key: unset`；帮助说明"不是持久化配置文件"
16. **T-2.07 CompletionCommand**（依赖 T-2.04，可降级到 Phase 3）
    - 文件：新建 `CompletionCommand.kt`
    - 变更：`cliktCompletionScriptFor` 支持 bash/zsh/fish/powershell
    - 验收：`completion bash` 过 `bash -n`；4 shell 输出非空无 `\r`
17. **T-2.08 集成 --version 到 clikt**（依赖 T-1.08/02）
    - 文件：新建 `command/VersionCommand.kt`、`ScoopAi.kt`
    - 变更：clikt `version` 字段直读 `Version.VERSION`；移除手写解析器
    - 验收：`--version` 与 `version` 子命令；无重复逻辑
18. **T-2.09 GenerateCommand 参数测试**（依赖 T-2.04）
    - 文件：新建测试（clikt `command.test(...)`，无 LLM）
    - 变更：测 help/必需 url/缺标志值/无效 persist/`--no-cache`/`--verify`
    - 验收：全部断言通过
19. **T-2.10 DoctorCommand 测试**（依赖 T-2.05）
    - 文件：新建测试（桩化 HealthCheck）
    - 变更：全通过(7/7,exit 0)/含警告(6/7,exit 0)/含失败(5/7,exit 1)
    - 验收：三场景退出码与计数正确
20. **T-2.11 ConfigShowCommand 测试**（依赖 T-2.06）
    - 文件：新建测试
    - 变更：重构 `ProviderConfigResolver` 接受 `Map<String,String>`；测默认 provider/api-key 脱敏/env 覆盖
    - 验收：无原始密钥泄露；env 覆盖生效；`core` 调用点不受影响
21. **T-2.12 CompletionCommand 测试**（依赖 T-2.07，可降级）
    - 文件：新建测试
    - 变更：4 shell 各自非空且含 `_scoop_ai_completion`
    - 验收：断言通过
22. **T-2.13 README 更新**（依赖 T-2.04/05/06/07）
    - 文件：`README.md`
    - 变更：列 4 子命令 + 全 16 标志（短名/长名/类型/默认/描述）+ Doctor/Completion 章节
    - 验收：标志表与契约一致
23. **T-2.14 向后兼容验证 + 清理**（依赖所有 Phase 2）
    - 文件：`Main.kt`、`CHANGELOG.md`
    - 变更：端到端测 `fd` + `jq`；删 Main 中手写解析；CHANGELOG 加 `### Changed`
    - 验收：Main.kt < 15 行；2 个端到端测试通过

### Phase 3 - 体验打磨 (v0.3.0)

24. **T-3.01 self-bootstrap**（依赖所有 Phase 2）
    - 文件：新建 `bucket/scoop-ai.json`、`README.md`、`CHANGELOG.md`
    - 变更：用生成 manifest 作种子；验证 `scoop install bucket/scoop-ai.json -s`；加 GitHub Releases `autoupdate`
    - 验收：安装+卸载干净；`scoop install scoop-ai` 可用
25. **T-3.02 troubleshooting.md**
    - 文件：新建 `docs/troubleshooting.md`
    - 变更：FAQ 10+ 问（NoWindowsAssets/LLMTimeout/ApiKeyInvalid/VerifyFailed...），每条含症状/原因/修复，先引导 `scoop-ai doctor`
    - 验收：覆盖常见失败路径
26. **T-3.03 providers.md**
    - 文件：新建 `docs/providers.md`
    - 变更：6 provider 的 env/默认 model/baseUrl 示例/注意事项；deepseek/openai-compatible 双重编码单独章节
    - 验收：6 provider 信息完整
27. **T-3.04 --output-format json**
    - 文件：`core/.../Types.kt`、`agent/ScoopManifestStrategy.kt`、`GenerateCommand.kt`、新建 `JsonFormatter.kt`
    - 变更：`ManifestResult`/`TokenUsage`/`StageInfo` 加 `@Serializable`；`ManifestResult` 增 `stages: List<StageInfo>`（4 条）；`StageInfo` 恰好 5 字段（phaseName/durationMs/inputTokens/outputTokens/toolCallCount）；引入 `GenerationObserver`（线程本地记当前阶段）；`JsonFormatter` 按契约序列化
    - 验收：`jq .stages[0].phaseName` 可用；4 阶段各 5 键；refine 含 review-agent token；文本模式不变
28. **T-3.05a RunArtifacts**（依赖 T-3.04）
    - 文件：新建 `core/.../artifacts/RunArtifacts.kt`、`SensitiveArtifactWriter.kt`，改 `ScoopManifestGenerator.kt`/`GenerateCommand.kt`
    - 变更：每次运行 `runId`（UUID，`--run-id` 可覆盖）；写 `.scoopai/runs/<id>/{research,analyze,generate}.json`；`--artifacts auto`(仅失败/非交互写)/`always`/`never`；全经 `SensitiveArtifactWriter`（包 T-1.04 脱敏器）；仅调试辅助，不支持恢复
    - 验收：失败写 >= 1 阶段 json（原子重命名）；成功无 `always` 不写；无原文密钥；`never` 不创建任何内容
29. **T-3.05b Checkpoint spike（可选，gated）**（依赖 T-3.05a）
    - 文件：新建 `core/.../artifacts/Checkpoint.kt`、`CheckpointResumeSpike.kt`（作文档测试）
    - 变更：判断 Koog 图 DSL 是否支持注入 `ResearchOutput`/`AnalyzeOutput` 并从中间节点启动（当前 strategy 用本地捕获变量 + 边，中间入口非一等特性）
    - 验收：输出 `RESUME_SUPPORTED`（可工作 `--from analyze` demo）或 `RESUME_DEFERRED`（5 行阻塞原因）记录到 CHANGELOG v0.3.0；DEFERRED 不含 `--from` 也不阻塞
30. **T-3.06 --cache=keep|discard**（依赖 Phase 2）
    - 文件：`GenerateCommand.kt`
    - 变更：`--cache <mode>` 枚举 keep/discard；`--no-cache` 作弃用别名(WARN)；默认 keep；导出 `keepCache: Boolean`
    - 验收：`--cache=discard` == `--no-cache`；`--no-cache` 给弃用警告；README 更新
31. **T-3.07 安全提示**
    - 文件：`README.md`
    - 变更：`> WARNING` 区块（shell 历史风险，推荐 direnv/keyring）；所有 `*_API_KEY` 标 `**(sensitive)**`；链 Phase 4 密钥环
    - 验收：WARNING 区块 + >= 3 敏感变量已标记
32. **T-3.08 man page**（依赖 Phase 2）
    - 文件：新建 `docs/man/scoop-ai.1`、`cli/build.gradle.kts`
    - 变更：章节 NAME/SYNOPSIS/DESCRIPTION/OPTIONS/EXIT STATUS/EXAMPLES/SEE ALSO；Gradle 复制 `.1` 到 `$buildDir/dist/man/man1/`
    - 验收：WSL/Linux `man -l` 可用；覆盖 4 子命令
33. **T-3.09 端到端 mock 测试**（依赖 T-3.04）
    - 文件：扩展 `ScoopManifestGeneratorMockTest.kt`、新建 `cli/.../e2e/CliE2ETest.kt`
    - 变更：每阶段一个 `getMockExecutor` mock，经 `GenerateCommand.test(...)` 触发；测成功路径(exit 0, `Written to`, 有效 JSON)与部分阶段失败回退(非零退出码, RunArtifacts 目录已创建)
    - 验收：两路径断言通过
34. **T-3.10 CHANGELOG 0.3.0**（依赖 T-3.01..09）
    - 文件：`CHANGELOG.md`
    - 变更：用 `release-note` skill 处理 `v0.2.0..HEAD`；加 `## [0.3.0]`；版本升 0.3.0；列 Phase 2-3 特性；`### Notes` 记 T-3.05b 结论
    - 验收：分类完整；spike 结论有记录
35. **T-3.11 切出 0.3.0**（依赖 T-3.10）
    - 文件：`gradle.properties`、`git tag v0.3.0`、GitHub Release
    - 变更：用 `release` skill + shadowJar；推送需用户确认
    - 验收：tag 存在；Release 资产含 jar

### Phase 4 - 配置 + 密钥环 (v0.4.0，按需触发)

36. **T-4.01 配置文件**（依赖触发条件满足）
    - 文件：`cli/.../config/ConfigFile.kt`、`ConfigLoader.kt`、`cli/build.gradle.kts`(加 `org.tomlj:tomlj`)、`CompositionRoot.kt`
    - 变更：TOML `[provider]/[github]/[cache]`；优先级 CLI 标志 > env > 配置文件 > 默认
    - 验收：`config show` 列每项及来源；缺文件正常
37. **T-4.02 密钥环**（依赖 T-4.01）
    - 文件：新建 `cli/.../secrets/KeyringProvider.kt`、`cli/build.gradle.kts`(加 JNA)
    - 变更：Windows `wincred`、macOS Keychain/Linux Secret Service 尽力而为；不可用回退 env + stderr WARN；`config set api-key` 写密钥环
    - 验收：Win11 `config set api-key` 后无需 env 即可运行；doctor 加 Keyring 检查(不可用 WARN)
38. **T-4.03 configuration.md**（依赖 T-4.01/02）
    - 文件：新建 `docs/configuration.md`
    - 变更：位置/格式/优先级/示例 + 各 OS 密钥环安装方式
    - 验收：README 链接可达
39. **T-4.04 切出 0.4.0**（依赖 T-4.03）
    - 变更：用 `release` skill；tag `v0.4.0`；推送需确认
    - 验收：触发条件已确认；tag 存在

## 依赖关系

```
T-1.01 -+-> T-1.08 -> T-2.08
        +-> T-1.09
T-1.02 -> T-1.03 -> T-1.04
T-1.05 -> T-1.07
T-1.06 (独立)

T-2.01 -> T-2.02 -+-> T-2.04 -+-> T-2.05 -> T-2.10
                  +-> T-2.06 -> T-2.11
                  +-> T-2.07 -> T-2.12   (可降级到 Phase 3)
                  +-> T-2.09
T-2.02 -> T-2.03
T-2.13 (需 T-2.04/05/06/07)    T-2.14 (需全部 Phase 2)

T-3.04 -> T-3.05a -> T-3.05b (spike, gated)
T-3.04 -> T-3.09    T-3.05a -> T-3.09
T-3.01..09 -> T-3.10 -> T-3.11
T-3.06 / T-3.08 (需 Phase 2)

T-4.01 -> T-4.02 -> T-4.03 -> T-4.04   (需触发条件)
```

- T-1.01 / T-1.05 / T-1.06 / T-3.02 / T-3.03 / T-3.04 / T-3.07 可并行启动
- Phase 4 全部为 gated（触发条件见下）

## 风险

| ID | 风险 | 严重 | 缓解 |
|----|------|------|------|
| R-1 | RunArtifacts/checkpoint 文件泄露 API 响应/token/头/LLM 请求体 | 高 | `SensitiveArtifactWriter` 写前包 T-1.04 脱敏器；测试覆盖 8+ 场景；`--artifacts never` |
| R-2 | stdout/stderr/JSON/进度歧义影响自动化 | 高 | 输出契约章节；`JsonFormatterTest` 断言 stdout 仅 JSON |
| R-3 | T-3.05b 结论 DEFERRED | 高(可接受) | 设计目的即暴露风险；DEFERRED 不阻塞 v0.3.0，作 Phase 4 候选触发条件 |
| R-4 | manifest 含 license/bin/persist/autoupdate 错误却过 `scoop install` | 高 | 输出是"草稿"；AGENTS.md 要求对比官方 bucket；README 注明"始终审查" |
| R-5 | clikt 与 coroutines/serialization 版本冲突 | 中 | T-2.01 先 spike，必要时降级/exclude |
| R-6 | LLM provider 不稳定 | 中 | `LlmProviderFactory` + Thinking 能力；冒烟测试；providers 文档(T-3.03) |
| R-7 | GitHub API 速率限制/token 难诊断 | 中 | `DoctorCommand` 查 token；research 区分 rate-limit/404/timeout |
| R-8 | 缓存语义歧义（下载/checkpoint/LLM/Scoop） | 中 | `--cache=keep\|discard` 仅限 Scoop 下载缓存；Phase 4 配置文档说明 |
| R-9 | Windows cmd `\r` / clikt ANSI 乱码 / shadowJar 体积 | 低 | `System.console()==null` 禁覆盖层；Main 禁 clikt ANSI；clikt 约 +200KB 可接受 |

## 验证方式

- **单元/命令/mock 测试**：`gradlew.bat :core:cleanTest :core:test --rerun-tasks` 与 `gradlew.bat :cli:test` 全绿
- **发版验证（第 4 层，每次发版）**：跑一个已验证示例（ripgrep/fd），执行 `scoop install .verify/<app>-ai-test.json -s`，与官方 bucket manifest（`C:\Users\xujl\scoop\buckets\main\bucket\` 或 `extras\bucket\`）逐字段对比 bin/license/extract_dir/shortcuts/persist/autoupdate
- **退出码验证**：每个退出码在 PowerShell `$LASTEXITCODE` / cmd `%ERRORLEVEL%` / bash `$?` 至少 2 种 shell 验证
- **脱敏验证**：默认输出无原文密钥；`SCOOP_AI_DEBUG_HTTP=true` 下仍脱敏
- **切版**：用 `release` skill（T-3.11/T-4.04），推送需用户确认

## 待确认问题

- Phase 4 触发条件当前是否已满足？（3+ issue 要持久化配置 / 不再每次导出 API_KEY / 维护者遇密钥痛点 / README 反馈"每次设 env"）未满足则 v0.4.0 不启动
- T-3.05b spike 若 DEFERRED，是否接受 v0.3.0 不含 `--from <stage>` checkpoint 恢复？
- T-2.01 clikt spike 是否确认无传递依赖冲突？（影响 T-2.02 之后所有 Phase 2 任务）
- T-4.01 TOML 库选 `org.tomlj:tomlj` 还是其他？JNA 在 Windows 是否需要 VS 运行时？（影响 T-4.02 备选 `java-keyring`）
