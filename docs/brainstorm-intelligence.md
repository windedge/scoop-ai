# Scoop AI Manifest 生成器 — 智能化改进脑暴

> 日期：2026-06-03
> 基于约 20 个 app 的实际测试结果

## 1. 当前架构

```
nodeStart → research(LLM+10工具) → analyze(LLM+4工具) → prepareManifest(确定性骨架) → generate(LLM,无工具) → verify(validate+review) → [通过] finish / [失败] refine(LLM,无工具,max3) → verify 循环
```

**核心数据流**：`ResearchOutput`(JSON) → `AnalyzeOutput`(JSON) → `assembleManifestSkeleton`(确定性JSON) → LLM 微调 → `verify`(结构校验+语义审查) → `refine`(轻量修复)

**关键约束**：
- 三阶段 LLM 会话是无状态的，阶段间仅通过结构化 JSON 传递信息
- verify 节点是确定性的（validate + review agent）
- refine 循环最大3次，且 refine LLM 无工具可用
- 参考 manifest 是硬编码的7个模板，不随目标 app 变化

---

## 2. 已知系统性问题

| # | 问题 | 影响 | 已测试的 app |
|---|------|------|-------------|
| 1 | 资产选择偏差 | 无脑偏好 zip/7z portable | git(MinGit)、imagemagick、yt-dlp、vlc |
| 2 | 功能字段缺失 | persist/pre_install/suggest 遗漏 | qaac、volume2、yt-dlp |
| 3 | license 判断不准 | GPL -only/-or-later、Proprietary 检测 | juce(Proprietary→GPL)、vlc、free-shooter |
| 4 | checkver/autoupdate 过于简单 | 对非 GitHub 源失效 | sqlite、vlc、volume2 |
| 5 | 安装程序类型检测不精确 | NSIS 3 vs 旧 NSIS 区分 | spyder(NSIS 3 无法提取) |

---

## 3. 核心洞察

> **根本问题不是 LLM 不够聪明，而是 LLM 缺乏领域知识。**

Scoop 社区 conventions、官方 manifest 模式、安装程序格式的细微差异——这些都是 LLM 难以仅凭 prompt 规则覆盖的领域知识。

因此，改进的核心思路是：**给 LLM 更多领域知识**。

---

## 4. 改进方案

### A. 架构改进

#### A1. 启发式相似 manifest 匹配（推荐优先实施）

**问题**：当前 `ReferenceManifests.ALL` 是7个固定模板，与目标 app 无关。LLM 需要自行推断 conventions，犯错概率高。

**方案**：从 research 阶段输出提取特征指纹，扫描本地 Scoop bucket（~3853个 manifest），找到结构最相似的 Top-K 作为参考注入 generate prompt。

**特征指纹**：

```
ManifestFingerprint {
    installer_type:  zip | 7z | msi | nsis | innosetup | portable_exe | tar
    app_type:        cli | gui | cli+gui
    has_multi_arch:  bool        // architecture 块有多个子项
    has_persist:     bool
    has_pre_install: bool
    has_shortcuts:   bool
    has_env_set:     bool
    license_family:  gpl | mit | apache | bsd | proprietary | other
}
```

**相似度权重**：

| 特征 | 权重 | 理由 |
|------|------|------|
| installer_type | 40 | 决定 manifest 整体结构 |
| app_type (gui/cli) | 25 | GUI 要 shortcuts/persist，CLI 要 bin/env_add_path |
| has_persist | 10 | persist 结构差异大 |
| has_multi_arch | 10 | 单 arch vs 多 arch 结构完全不同 |
| has_pre_install | 10 | pre_install 模式是关键参考 |
| license_family | 5 | 影响较小 |

**流程**：

```
Research 阶段结束
  → 提取 ManifestFingerprint
  → 扫描本地 main+extras bucket (3853个manifest)
  → 计算相似度分数
  → 取 Top-2 注入 generate prompt
  → generate LLM 参考这些模板生成
```

**优势**：
- 不依赖 app 名称 — 新 app 也能找到相似参考
- 零 token 成本 — 确定性计算，不消耗 LLM 调用
- 覆盖面广 — 3853 个官方 manifest 作为模板库
- 直接对齐社区标准 — 参考的是已验证的官方 manifest

**示例**：
- 生成 spyder：installer_type=nsis, app_type=gui → 匹配 obs-studio、vlc
- 生成 ripgrep：installer_type=zip, app_type=cli, multi_arch → 匹配 fd、bat

| 维度 | 评估 |
|------|------|
| 实现难度 | **中** — 需要特征提取器 + bucket 扫描 + 相似度计算 + prompt 注入 |
| 预期效果 | **极高** — 直接解决 license、checkver、persist 三大问题 |
| 架构兼容性 | **好** — 在 prepareManifest 之前插入，不改变核心流程 |
| 优先级 | **P0** |

---

#### A2. 资产选择质量评分器

**问题**：LLM 系统性偏好 zip/7z portable，忽略版本号差异和格式适当性。

**方案**：在 analyze 阶段 LLM 输出 `AnalyzeOutput` 后，增加确定性后处理节点，对选择的资产进行质量评分和纠偏：

**评分规则**：
1. **版本优先**：最高版本 +2 分
2. **完整性优先**：包含 64bit+32bit+arm64 各 +1 分
3. **文件大小合理**：>500MB 的单个资产 -1 分
4. **hash 可用性**：有 GitHub API hash +1 分

| 维度 | 评估 |
|------|------|
| 实现难度 | **小** — 纯确定性函数 |
| 预期效果 | **中** — 减少资产选择错误 |
| 架构兼容性 | **好** — 在 analyze subgraph 末尾插入节点 |
| 优先级 | **P1** |

---

#### A3. 安装沙箱验证

**问题**：verify 阶段只做 JSON 结构校验和语义审查，无法检测安装可行性。spyder 的 NSIS 3 `#/dl.7z` 失败正是这种盲区。

**方案**：在 verify 节点中增加可选的第三步 — 实际安装测试：

- `--verify-install` CLI 参数开启
- 写入临时 manifest → `scoop install` → 检查退出码 → `scoop uninstall`
- 失败时提取 scoop 错误信息并自动推断修复策略
  - "Can't shim ... File doesn't exist" → extract_dir 或 bin 路径错误
  - "Hash mismatch" → 重新计算 hash
  - "Failed to extract" → 安装程序格式不兼容

| 维度 | 评估 |
|------|------|
| 实现难度 | **小** — `scoop install` 调用 + 错误解析 |
| 预期效果 | **高** — 直接发现安装可行性问题 |
| 架构兼容性 | **好** — 作为 verify 的第三步，可选开关 |
| 优先级 | **P1** |

---

### B. 工具能力提升

#### B1. PE 二进制分析（解决 NSIS 3 检测）

**问题**：`detectInstallerType` 只读文件头 1MB 做字符串匹配，无法区分 NSIS 2 vs NSIS 3。

**方案**：扩展 `detectInstallerType`：
1. 在 overlay data 中搜索 NSIS 版本标记
2. 用 `7z l` 尝试列出内容，若失败 → 标记为 `nsis_unsupported`
3. 若 NSIS 不可提取，自动检查是否有 zip/msi 替代资产

**最小可行版本**：用 7z 提取测试作为 NSIS 版本判别的关键信号，不需要完整 PE 解析。

| 维度 | 评估 |
|------|------|
| 实现难度 | **中** — 可渐进实现（先用 7z 测试） |
| 预期效果 | **高** — 直接解决 spyder 类 NSIS 3 问题 |
| 优先级 | **P1** |

---

#### B2. 启发式相似 manifest 查找工具（与 A1 配合）

为 LLM 提供新工具 `findSimilarManifests(features)`，返回 Top-K 相似官方 manifest 的摘要（非全文，避免 token 爆炸）。

每个匹配返回：
- `installer_type`、`app_type`（结构参考）
- `license`（精确 SPDX 格式）
- `checkver` 策略（github / url+regex / jsonpath）
- `autoupdate` 结构
- `persist` 列表
- `pre_install` 模式

| 维度 | 评估 |
|------|------|
| 实现难度 | **小** — 读本地文件系统 + 相似度计算 |
| 预期效果 | **极高** — 对齐社区最佳实践 |
| 优先级 | **P0** — 与 A1 配合实施 |

---

### C. 策略优化

#### C1. 定向修复增强

**问题**：refine 的 feedback 是扁平化错误列表，LLM 不知道哪些必须改、哪些可选。

**方案**：将 feedback 升级为结构化修复指令：

```kotlin
data class FixInstruction(
    val field: String,        // "bin", "license", "checkver"
    val action: FixAction,    // REPLACE, REMOVE, ADD, TRANSFORM
    val severity: FixSeverity, // ERROR, WARNING
    val suggestedValue: String?,
)
```

- `REPLACE`（如 license 格式错误）→ 直接替换
- `REMOVE`（如多余的 bin）→ 不需要 LLM 判断
- 减少自由发挥空间 → 减少 refine 引入新错误

| 维度 | 评估 |
|------|------|
| 实现难度 | **中** |
| 预期效果 | **高** — refine 更精准 |
| 优先级 | **P1** |

---

#### C2. 安装程序格式变更检测

**问题**：app 版本升级后安装程序格式可能改变（如 7z → NSIS 3）。

**方案**：在 research 阶段比较最近 3 个版本的 release assets 格式分布，检测变更时在 analyze prompt 中标注警告。

| 维度 | 评估 |
|------|------|
| 实现难度 | **小** — 确定性比较逻辑 |
| 预期效果 | **中** — 需求频率低 |
| 优先级 | **P2** |

---

### D. 评估与反馈

#### D1. 自动化 diff 评分系统

**方案**：构建 ManifestDiffScore，对每个字段计算得分（0-100）：

| 字段 | 权重 | 评分规则 |
|------|------|---------|
| version | 10% | 完全相同 → 100 |
| license | 15% | SPDX 完全匹配 → 100 |
| url | 15% | URL 模式匹配 → 90 |
| hash | 5% | 格式正确 → 100 |
| bin | 15% | 集合等价 → 100 |
| shortcuts | 10% | 集合等价 → 100 |
| persist | 10% | 集合等价 → 100 |
| checkver | 10% | 策略等价 → 100 |
| autoupdate | 10% | URL 模板正确 → 100 |

用于：量化改进效果、回归检测、CI 集成。

| 维度 | 评估 |
|------|------|
| 实现难度 | **小** |
| 预期效果 | **高** — 量化改进、回归检测 |
| 优先级 | **P1** |

---

#### D2. 回归测试套件

**方案**：对每个成功的 app 保存 golden output + research cache，修改代码后用缓存数据重跑，自动 diff 输出。

| 维度 | 评估 |
|------|------|
| 实现难度 | **中** |
| 预期效果 | **高** — 避免回归，加速迭代 |
| 优先级 | **P1** |

---

#### D3. 人工反馈回路

**方案**：用户在 manifest 旁写 `.diff.json` 批注，下次生成同类 app 时检索注入 prompt。

| 维度 | 评估 |
|------|------|
| 实现难度 | **中** |
| 预期效果 | **高** — 持续学习 |
| 优先级 | **P2** — 需要积累数据 |

---

### E. 用户体验

#### E1. 生成后自动安装验证（`--auto-verify`）

一条命令完成：生成 → 安装 → 卸载 → 失败则自动 refine → 输出验证过的 manifest

安装失败时解析 scoop 错误信息，自动推导修复策略：
- "Can't shim X.exe" → 检查 extract_dir / bin 路径
- "Hash mismatch" → 重新计算 hash
- "Failed to extract" → 安装程序格式不兼容

| 维度 | 评估 |
|------|------|
| 实现难度 | **中** |
| 预期效果 | **极高** |
| 优先级 | **P0** |

---

#### E2. 置信度评分

为每个字段计算置信度，低置信度字段用颜色标记：

| 来源 | 置信度 |
|------|--------|
| GitHub API 直接返回 | 0.98 |
| 确定性代码生成 | 0.95 |
| LLM 选择（基于 findExecutables） | 0.75 |
| LLM 推断（license from README） | 0.50 |
| LLM 猜测 | 0.30 |

| 维度 | 评估 |
|------|------|
| 实现难度 | **小** |
| 预期效果 | **中** |
| 优先级 | **P1** |

---

#### E3. Diff 模式（`--diff`）

并排对比生成 vs 官方 manifest，彩色高亮差异，每个差异标注严重程度和修复建议。

| 维度 | 评估 |
|------|------|
| 实现难度 | **小** |
| 预期效果 | **高** |
| 优先级 | **P1** |

---

## 5. 优先级总览

### P0（立即做，最大回报）

| # | 方案 | 难度 | 解决的核心问题 |
|---|------|------|---------------|
| A1/B2 | 启发式相似 manifest 匹配 + 查找工具 | 中 | license、checkver、persist 准确率 |
| E1 | 生成后自动安装验证 | 中 | 用户一条命令得到验证过的 manifest |

### P1（短期高回报）

| # | 方案 | 难度 |
|---|------|------|
| A2 | 资产选择质量评分器 | 小 |
| A3 | 安装沙箱验证 | 小 |
| B1 | PE 二进制分析（NSIS 3 检测） | 中 |
| C1 | 定向修复增强 | 中 |
| D1 | 自动化 diff 评分 | 小 |
| D2 | 回归测试套件 | 中 |
| E2 | 置信度评分 | 小 |
| E3 | Diff 模式 | 小 |

### P2（中长期探索）

| # | 方案 | 难度 |
|---|------|------|
| C2 | 安装程序格式变更检测 | 小 |
| D3 | 人工反馈回路 | 中 |

---

## 6. 实施路线图

```
Phase 1: 启发式匹配（P0）
├── ManifestFingerprint 特征提取器
├── Bucket 扫描 + 相似度计算
├── Generate prompt 注入参考模板
└── 测试验证

Phase 2: 自动验证（P0）
├── CLI --auto-verify 参数
├── scoop install/uninstall 自动化
├── 错误解析 + 修复策略映射
└── refine 集成

Phase 3: 质量工具（P1）
├── Diff 评分系统
├── 置信度评分
├── 回归测试套件
└── 资产选择评分器

Phase 4: 深度改进（P1-P2）
├── PE 二进制分析
├── 定向修复增强
├── 格式变更检测
└── 人工反馈回路
```
