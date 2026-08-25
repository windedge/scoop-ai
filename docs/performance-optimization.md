# Scoop-AI Manifest 生成速度优化方案

## 当前状态

- 平均生成时间：~166s（2m46s）
- 最快：108s（简单 CLI 工具）
- 最慢：207s（NSIS + Electron 两层提取）

### 各阶段耗时分布

| 阶段 | 占总时间 | LLM 调用次数 | 说明 |
|------|---------|-------------|------|
| Research | 48%~76% | 8~20 次 | 绝对瓶颈，LLM 充当探索者 |
| Analyze | 5%~24% | 2~4 次 | 多架构包需逐个分析 |
| Generate | 5%~17% | 2 次 | 审查骨架 + 输出最终 manifest |
| Verify | 5%~16% | 1 次 | Review agent 独立调用 |
| Refine | 偶尔 | 0~3 轮 | 大多数一次通过 |

### Token 消耗

Input tokens 远大于 output tokens（约 50:1~80:1）：

| 案例 | Input Tokens | Output Tokens |
|------|-------------|---------------|
| go-jsonnet | 256,900 | 7,963 |
| gitui | 687,950 | 10,010 |
| faas-cli | 620,528 | 8,342 |
| kotlin-shell | 511,150 | 12,407 |

Prompt 大小：
- PhasePrompts.kt: 44.7KB
- PhaseSchemas.kt: 8.4KB
- ReviewPrompts.kt: 6.8KB
- ReferenceManifests.kt: 10.7KB

---

## 瓶颈分析

### 瓶颈 1：Research 阶段 LLM 迭代循环（占 ~60%）

LLM 充当"探索者"，每调用一个工具就需一次 LLM 推理来决定下一步：

```
LLM → parseGitHubUrl → LLM → fetchRepoInfo+fetchLatestRelease → LLM → 
fetchFileContent/LICENSE → LLM → searchTextInArchive → LLM → 
补充数据 → LLM → ... → finalize_task_result → LLM
```

- 每次 LLM 调用需 2~10s 推理时间
- 每次调用都重传累积的 context（7K → 60K+ tokens）
- gitui 案例：19 次工具调用 + 16 次 LLM 推理

### 瓶颈 2：searchTextInArchive 逐轮搜索

100% 的 app 都调用 searchTextInArchive，且经常需要多轮：

| App | 调用次数 | 搜索内容 |
|-----|---------|---------|
| gitui | 10 | 先通用模式，再针对性搜 GITUI_CONFIG 等 |
| faas-cli | 10 | 通用模式 + goreleaser 配置 |
| kotlin-shell | 10 | 通用模式 + Kotlin 特定模式 |
| go-jsonnet | 4 | 通用模式 |
| azuredatastudio | 1 | 通用模式 |

LLM 每次只搜一组关键词，发现线索后再针对性搜索。如果预收集阶段一次性用所有 ~30 个关键词搜索，可以把 4~10 次调用压缩为 1 次。

### 瓶颈 3：累积 Context Window

Research prompt ~20K chars，每次工具返回结果累积在 context 中：
- 后期 LLM 调用 input tokens 达 50K~70K
- 包含大量"看过但不需要"的数据（linux assets、完整 API 响应等）

### 瓶颈 4：LLM 低效工具使用

- gitui 中 LLM 反复用 fetchJson 尝试获取 asset hash（8 次失败/截断）
- faas-cli 因 API 限流被迫爬 HTML 页面（12 次低效 fetchWebPage）
- LLM 经常在已有足够信息时仍继续探索

---

## 优化方案

### Phase 1：高 ROI 改动（预计 166s → ~90s）

#### A. Research 预收集（节省 40~60s）

在 LLM 介入前，由确定性代码一次性并行完成所有数据收集：

```kotlin
// ResearchPrefetch — 确定性数据收集器
class ResearchPrefetch {
    suspend fun collect(owner: String, repo: String): PreResearchData {
        return coroutineScope {
            // 并行收集（所有请求同时发起）
            val repoInfo = async { githubApi.fetchRepoInfo(owner, repo) }
            val releases = async { githubApi.fetchLatestRelease(owner, repo) }
            val license = async { githubApi.fetchFileContent(owner, repo, "LICENSE") }
            val readme = async { githubApi.fetchFileContent(owner, repo, "README.md") }
            
            // 等待 release 信息后，并行处理 assets
            val release = releases.await()
            val windowsAssets = filterWindowsAssets(release)
            val hashes = async { computeHashes(windowsAssets) }
            
            // 一次性搜索所有配置模式
            val configPatterns = async {
                searchTextInArchive(
                    sourceUrl = release.sourceZipUrl,
                    patterns = ALL_CONFIG_PATTERNS // ~30 个关键词一次搜完
                )
            }
            
            PreResearchData(
                repoInfo = repoInfo.await(),
                release = release,
                license = license.await(),
                readme = readme.await(),
                windowsAssets = windowsAssets,
                hashes = hashes.await(),
                configPatterns = configPatterns.await()
            )
        }
    }
    
    companion object {
        val ALL_CONFIG_PATTERNS = listOf(
            // 标准配置路径
            "XDG_CONFIG_HOME", "APPDATA", "LOCALAPPDATA", "user.home", ".config",
            // 通用目录名
            "data", "config", "logs", "storage", "media",
            // 配置 API
            "env::var", "getenv", "os.Getenv", "System.getenv",
            // 环境覆盖变量
            "_CONFIG_DIR", "_CONFIG_PATH", "_CONFIG_HOME", "_DATA_DIR",
            "_HOME", "_LIB", "_CFG", "_PATH", "_CACHE"
        )
    }
}
```

**改动点：**
- 新增 `core/src/commonMain/kotlin/com/scoopai/core/agent/ResearchPrefetch.kt`
- 修改 `ScoopManifestStrategy.kt` — 在 research subgraph 前注入预收集结果
- 简化 `buildResearchPrompt` — 预收集结果已注入，LLM 只需"分析与输出"

**效果：**
- Research LLM 调用从 15 次降到 2~3 次
- searchTextInArchive 调用从 4~10 次降到 1 次
- 所有 HTTP 请求并行执行（当前串行）

#### B. 压缩 Prompt（节省 15~25s）

1. **ReferenceManifests 按需注入**：当前总是包含 ~10.7KB 参考样例，改为根据安装器类型只注入相关的 2~3 个
2. **Research prompt 简化**：预收集结果替代工具探索描述（减少 ~5KB prompt）
3. **PhaseSchemas 精简**：移除示例中的冗余规则说明

**改动点：**
- `PhasePrompts.kt` — 精简 research prompt
- `ReferenceManifests.kt` — 按需选择函数
- `PhaseSchemas.kt` — 精简描述

---

### Phase 2：低成本改善（预计 ~90s → ~65s）

#### C. 工具调用预算强制执行（节省 20~40s）

- Research 阶段工具调用硬限制从 10 次降到 6 次
- 达到限制前 2 次时给 LLM "即将达到限制"提示
- 拦截重复调用同一工具（如 gitui 中 8 次 fetchJson）

**改动点：**
- `ScoopManifestStrategy.kt` — 工具调用计数拦截
- `PhasePrompts.kt` — 更严格的使用指导

#### D. Review Agent 轻量化（节省 5~15s）

- 确定性规则替代 review agent 的部分检查（extract_dir 深度、license 格式、bin 位置）
- 仅将语义检查交给 LLM（bin 是否合理、persist 是否必要）

**改动点：**
- `ScoopManifestStrategy.kt` — normalize 节点增强确定性规则
- `ReviewPrompts.kt` — 缩减 review scope

---

### Phase 3：进阶优化（可选）

#### E. 工具结果摘要（节省 10~20s）

在将工具结果返回 LLM 前，进行智能摘要：
- fetchRepoInfo：只保留 description/homepage/license/default_branch
- fetchLatestRelease：只保留 tag_name + Windows 相关 assets
- fetchJson（repo tree）：只保留关键文件路径
- searchTextInArchive：限制返回 match 数量为 10 条

**风险**：过度摘要可能丢失关键信息

#### F. LLM 结果缓存（节省 20~40s，重复项目）

- fetchRepoInfo/fetchLatestRelease 缓存到磁盘（TTL 1 小时）
- searchTextInArchive 结果按 URL 缓存
- 同一项目多次生成只需下载/搜索一次

#### G. 合并 Research+Analyze（节省 10~15s，高难度）

将 analyze 工具也纳入 research 阶段，消除阶段切换的 context 重建开销。

#### H. 确定性 Research 替代 LLM（节省 60~100s，最激进）

将 Research 阶段完全改为确定性代码，仅用 LLM 处理"模糊判断"。

---

## 实施优先级

| 优先级 | 方案 | 节省 | 难度 | 风险 | ROI |
|--------|------|------|------|------|-----|
| **P0** | A: Research 预收集 | 40~60s | 中 | 低 | ★★★★★ |
| **P0** | B: 压缩 Prompt | 15~25s | 低 | 低 | ★★★★ |
| **P1** | C: 工具调用预算 | 20~40s | 低 | 中 | ★★★ |
| **P1** | D: Review 轻量化 | 5~15s | 低 | 低 | ★★★ |
| P2 | E: 工具结果摘要 | 10~20s | 中 | 中 | ★★ |
| P2 | F: LLM 结果缓存 | 20~40s | 低 | 低 | ★★ |
| P3 | G: 合并阶段 | 10~15s | 高 | 中 | ★ |
| P3 | H: 确定性 Research | 60~100s | 高 | 高 | ★ |

## 预期效果

| 阶段 | 当前 | Phase 1 | Phase 2 | Phase 3 |
|------|------|---------|---------|---------|
| 平均耗时 | 166s | ~90s | ~65s | ~40s |
| Research LLM 调用 | 15 次 | 2~3 次 | 2~3 次 | 0~1 次 |
| searchTextInArchive | 4~10 次 | 1 次 | 1 次 | 1 次（缓存） |

## 涉及文件

### Phase 1
- 新增：`core/src/commonMain/kotlin/com/scoopai/core/agent/ResearchPrefetch.kt`
- 修改：`core/src/commonMain/kotlin/com/scoopai/core/agent/ScoopManifestStrategy.kt`
- 修改：`core/src/commonMain/kotlin/com/scoopai/core/agent/prompt/PhasePrompts.kt`
- 修改：`core/src/commonMain/kotlin/com/scoopai/core/agent/ReferenceManifests.kt`
- 修改：`core/src/commonMain/kotlin/com/scoopai/core/agent/prompt/PhaseSchemas.kt`

### Phase 2
- 修改：`core/src/commonMain/kotlin/com/scoopai/core/agent/ScoopManifestStrategy.kt`
- 修改：`core/src/commonMain/kotlin/com/scoopai/core/agent/prompt/ReviewPrompts.kt`

### Phase 3
- 修改：`tools/src/jvmMain/kotlin/com/scoopai/tools/github/JdkHttpGitHubApi.kt`
- 修改：`tools/src/jvmMain/kotlin/com/scoopai/tools/archive/ZipArchiveInspector.kt`
- 新增：`tools/src/jvmMain/kotlin/com/scoopai/tools/cache/ResponseCache.kt`
