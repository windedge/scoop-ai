## 项目概况

基于 Koog AI Agent 的 Scoop Manifest 生成器。三阶段 agent（research / analyze / generate）。

## 关键文件

- `core/src/commonMain/kotlin/com/scoopai/core/agent/prompt/PhasePrompts.kt` — 三阶段 prompt
- `core/src/commonMain/kotlin/com/scoopai/core/agent/prompt/ReviewPrompts.kt` — review agent prompt
- `core/src/commonMain/kotlin/com/scoopai/core/agent/ReferenceManifests.kt` — 参考 manifest
- `core/src/commonMain/kotlin/com/scoopai/core/ScoopManifestGenerator.kt` — 三阶段 agent 主流程
- `core/src/commonMain/kotlin/com/scoopai/core/agent/ScoopManifestStrategy.kt` — 策略图（含配置）
- `tools/src/jvmMain/kotlin/com/scoopai/tools/llm/LlmProviderFactory.kt` — DeepSeek openai-compatible workaround
- `tools/src/jvmMain/kotlin/com/scoopai/tools/manifest/FileManifestIo.kt` — manifest 写入/校验
- `cli/src/main/kotlin/com/scoopai/cli/Main.kt` — CLI 入口

## Koog workaround 台账（收口于 KoogCompat）

项目针对 Koog 各版本缺陷保留的运行时 workaround 已收口到
`core/src/commonMain/kotlin/com/scoopai/core/llm/KoogCompat.kt` 单一 seam，
三条入口：`decorateHttpClient`（HTTP 拦截链装配：stripToolChoice + 三个响应
改写，openaiCompatibleOnly=true 时启用）、`decorateModel`（运行时剥
ToolChoice 能力）、`finishTool`（internal LenientStringFinishTool）。同族
vendored 拷贝 `AssistSubgraphWithImages.kt` 留在 agent 包原地。每条的触发条件
与退休条件写在 KDoc 里，升级 Koog 时逐条核对 KDoc，本文件不再重复维护清单。

已退休条目保留两条如下：

- **已完成：fixDoubleEncoding 随 Koog 1.1.1 删除。** 该拦截器修复 Koog 1.0.0 对
  tool call arguments / tool result content 的二次编码（Koog #2096）。1.1.1 已在上游
  修复根因，故 `RequestInterceptors.fixDoubleEncoding` 及其辅助函数
  `fixAssistantMessage` / `fixToolMessage` / `unwrapIfDoubleEncoded` 整体删除，
  `LlmProviderFactory` 拦截器链去掉 `add(::fixDoubleEncoding)`。回归证据：
  `tools/.../llm/ReasoningRetryStubGatewayTest` 断言网关捕获到的真实请求体不再含
  二次编码特征；`core/.../llm/RequestInterceptorsTest` 断言单编码请求体直通保留。

- **已完成：openai-compatible 能力层 DeepSeek 特判删除。** 原来
  `LlmProviderFactory.openAICompatibleCapabilities(modelName)` 对含 "deepseek" 的
  模型名注入 `Thinking` 并剥离 `ToolChoice`，`defaultModel()` 另借
  `DeepSeekV4Flash` 的 contextLength/maxOutputTokens，`isDeepSeekModelName()`
  在拦截器链决定是否启用 downgradeToolChoice。现能力统一为不含 `Thinking` 的
  标准集（Temperature/ToolChoice/Schema.Basic/Standard/Tools/Completion/
  OpenAIEndpoint.Completions），`defaultModel()` 走通用模型名分支，
  `isDeepSeekModelName()` 及所有 DeepSeek 特判分支整体删除（deepseek provider
  本身的 `deepSeekCapabilities()` 不受影响）。推理重试由运行时
  `ScoopManifestGenerator.modelWithoutToolChoice` 剥离 ToolChoice 保证，与能力
  声明无关。

## 构建命令

```bat
cd C:/Seafile/Works/kotlin/scoop-ai && cmd //c gradlew.bat :cli:shadowJar
```

输出：`cli/build/libs/cli-0.1.22-all.jar`

## 运行命令模板

```bat
cd C:/Seafile/Works/kotlin/scoop-ai && cmd //c "set SCOOP_AI_PROVIDER=openai-compatible&& set SCOOP_AI_API_KEY=sk-xxxx&& set SCOOP_AI_BASE_URL=http://192.168.40.130:8317&& set SCOOP_AI_MODEL=opencode/deepseek-v4-flash&& java -jar cli/build/libs/cli-0.1.22-all.jar <GitHub URL>"
```

## 验证流程（每次改动后必须执行）

改动后必须按以下三步完整验证，不能跳过：

统计测试数用 scripts/test-report.py，不要手写 grep/python 管道。

### 1. 跑一个包

用上面的运行命令生成 manifest，确保三阶段（research / analyze / generate）全部成功，无超迭代错误。

### 2. 安装测试

将生成的 manifest 复制为测试名再安装，避免和已安装的正式包冲突：

```bat
cd C:/Seafile/Works/kotlin/scoop-ai
mkdir -p .verify
cp bucket/<app>.json .verify/<app>-ai-test.json
scoop install C:\Seafile\Works\kotlin\scoop-ai\.verify\<app>-ai-test.json -s
```

确认安装成功后立即卸载清理：

```bat
scoop uninstall <app>-ai-test
```

### 3. 对比官方 manifest

将生成的 `bucket/<app>.json` 与本地 Scoop bucket 中的官方 manifest 逐字段比较：

- 官方 main bucket 路径：`C:\Users\xujl\scoop\buckets\main\bucket\`
- 官方 extras bucket 路径：`C:\Users\xujl\scoop\buckets\extras\bucket\`

重点关注：
- `bin` 是否包含不该有的辅助 exe
- `license` 是否准确（`-only` / `-or-later` 后缀、多 license）
- `extract_dir` 是否正确
- `shortcuts` / `persist` / `pre_install` 是否符合 GUI 包最佳实践
- `checkver` / `autoupdate` 是否可靠

## 测试

写或修改测试时（mock executor / HTTP stub / 策略图测试 / JSON 编码技巧 / 序列化用例），先读 docs/testing-guide.md；其中的文件清单与模式以该文件为准。

## 参考项目
- koog: C:\src\kotlin\koog

## 开发流程：TDD（测试驱动开发）

所有功能开发必须遵循 TDD 循环：**红 → 绿 → 重构**。

### TDD 规则

1. **先写测试**：任何新功能或 bug 修复，先写一个会失败的测试（红）
2. **最小实现**：写刚好让测试通过的代码（绿），不过度设计
3. **重构**：测试通过后，再优化代码结构，每次重构后确认测试仍然通过
4. **每一步都跑测试**：写完测试后跑一次，实现后跑一次，重构后跑一次

### 具体步骤

```
1. 分析需求 → 拆分为小的可测试单元
2. 写测试 → gradlew.bat :scoop-ai-core:cleanJvmTest :scoop-ai-core:jvmTest --rerun-tasks（预期失败）
3. 写实现代码（刚好让测试通过）
4. gradlew.bat :scoop-ai-core:cleanJvmTest :scoop-ai-core:jvmTest --rerun-tasks（预期全部通过）
5. 如需重构 → 重构后再次跑测试确认不破坏
6. 全部通过后提交
```

## 注意事项

- 不要自动提交 git，除非用户明确要求
- 当前剩余问题是 agent 策略层面，不是协议层面
- 已验证成功的包：ripgrep、fd、WinMerge、SumatraPDF、WezTerm、CopyQ
- **开发新功能时先写测试，测试驱动实现**
- 任务书引用代码用符号名（函数/类/常量名），行号仅作提示会过期
- 验证基线数字不权威，报告实际测试数与失败清单
