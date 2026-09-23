# 设计：Scooper 跳过 Save to Bucket 直接安装（Direct Install / 本地文件直装）

状态：提案（未实现）。基于 scooper 现状 + 本机 Scoop 源码
（`C:/Users/xujl/scoop/apps/scoop/current/libexec/scoop-install.ps1`、
`lib/manifest.ps1`、`libexec/scoop-update.ps1`）核实。

## 0. Scoop 行为核实结论（设计依据）

| 问题 | 结论（来自 Scoop 源码） |
|------|------------------------|
| `scoop install <file.json>` 支持 `-f/--force` 吗 | **不支持**。`scoop-install.ps1` getopt 只接受 `g i k s u a`；`--force` 会被 getopt 拒绝 |
| 同名已装时 install 的行为 | 提前短路：`warn "'$app' ($curVersion) is already installed"` + **exit 0**（不装、不报错）。判断依据是 `versiondir` 下的版本目录，与 manifest 来源无关 |
| 本地文件安装后 install.json 记录什么 | `save_install_info` 写 `{architecture, url, bucket}`；本地文件走 `Get-Manifest` 的 `Test-Path($app)` 分支 -> `url = Convert-Path $app`（绝对路径），`bucket = $null` 被 strip -> install.json 只有 architecture + url |
| 升级本地文件安装的 app（无 `--force`） | `scoop update <app>` 读 install.json：`bucket=null -> bucket='main'`，`url=<本地临时文件路径>`。文件已删时 `url_manifest` 返回 null -> `app_status` 令 `removed=true`、`latest_version`=已装版本 -> `outdated=false`；`scoop-update.ps1:432` 的 `if ($status.installed -and ($force -or $status.outdated))` 为假，落到 L442 打印 **`<app>: <ver> (latest version)`（绿色、误导，不报错）**。`No manifest available for '<app>'.`（`scoop-update.ps1:290`）只有在 `--force`（或 FORCE_UPDATE）时才会到达 |
| 重装名称 | 本地文件安装的 app 名 = manifest 文件名（`appname_from_url`）；文件内容里的 `name` 字段不影响 |
| URL manifest 安装 | `scoop install https://...json` 可以；app 名 = URL 最后一段去 .json。但装完 install.json.url 是那个 URL，只要 URL 内容更新，`scoop update <app> --force` 可用 |

## 1. 推荐方案（安装机制）

**复用 Test Install 的"写临时 manifest + `scoop install <tempFile>`"管线，但走专用方法，不卸载、名字用真实 app 名。**

在 `ScoopClient` 新增：

```kotlin
/** Direct-install the current manifest from a local file (no bucket). */
suspend fun installFromManifest(app: App, manifestFile: File): CommandResult

/** Build direct-install args; internal pure function so tests can assert assembly
 *  (对称于 uninstallArgs / updateArgs，模板见 ScoopClient.kt:352-359). */
internal fun installFromManifestArgs(name: String, manifestFile: File, global: Boolean): List<String>
```

[I6]/[S5] 的失败语义与命令形态如下（`sudo scoop ... -g` 前缀沿用
`ScoopClient.kt:301-322` 的既有约定）：

命令形态（推荐 **uninstall + install 两步**，不用 `-f`）：

```
已装同名（local 或 global，按 snapshot 的 isGlobal 决定 scope）:
    scoop uninstall <name> [-g]        # 不带 -p，保留 persist 数据
    scoop install <tempFile> [-g]
未装:
    scoop install <tempFile>
```

理由：

1. scoop install 不支持 `--force`，已装时会被 already-installed 短路 exit 0，UI 会误报成功
   ——复用 `evaluateInstallOutput` 也无法从输出区分。必须先卸。
2. 先卸后装 = Test Install 已验证过的同一套原语（`testInstallManifest` 的 uninstall 段 +
   `scoop install <file> -s`），shim 测试基建可以直接覆盖。
3. 卸载默认保留 `persist/<app>`（项目里 uninstall 的 `-p` 语义注释已明确），重装后数据仍在；
   shim/shortcut 被 uninstall 清掉后由 install 重建，与 scoop 原生 update 的
   "rm_shims -> install_app" 序列效果一致。
4. 缓存预热直接复用 `prewarmTestInstallCache(app.name, manifestFile)`——它就是按
   `{appName}#{version}#{hash}` 命名规则给本地文件安装准备缓存的方法，传真实 app 名即可。

**否决的备选**：
- `scoop install <tempFile> -f`：flag 不存在，getopt 报错退出。
- "不重命名 manifest、直接 `scoop install <file>` + 依赖 scoop 短路"：已装场景静默无操作。
- URL 形态（data: URL 或起本地 http）：app 名无法控制成真实名，url 落进 install.json 后
  升级语义更怪，纯增复杂度。

**并发防护（[S3]）**：`ScoopClient` 层对同名 app 的直装做串行/互斥——uninstall+install 是非原子序列，
若两个请求并发（用户连点、AI 与手动同时触发）会交错导致 install 撞上正在卸载的状态。复用
`ScoopClient` 既有的 `TaskQueue`（命令执行已串行化）或按 name 加轻量锁；VM 层 `installing` 标志
只能防 UI 连点，不能防跨入口并发。

**临时文件位置与生命周期**：内容 = 当前 `activeManifestJson`（不做 `rewriteManifestName`；
[name 字段见 §3](#3-ui--按钮改动点)）。装完后 **删除临时文件**（install.json 里的 url 指向它，
但按下面的策略升级永远走 uninstall+install，不依赖该路径；也见 §1.4 的 bucket 改写方案）。

位置建议**不要用 `%TEMP%`**：`%TEMP%` 会被清理工具删除、路径随机，使"url 指向的文件"更易失联。
改用稳定目录下的按 app 名固定路径，例如 `%LOCALAPPDATA%\scooper\direct-install\<appName>.json`，
这样即使不清删，同一次直装/重装的可预测路径也让"手动 scoop update 找得到文件"成为可能（见 §6 第 4 条）。
是否删除临时文件属于有意取舍（Q2）：删除 = 干净但悬空；保留 = 悬空引用概率下降但留有残留文件。
本设计**默认删除**、统一在 `finally` 清理（§1.5）。

### 1.1 已装场景（Reinstall 语义）

`onInstallSaved` / AI 路径统一成一条规则：

- **manifestSaved=true 且 saveResult 有效** -> 现有 bucket 路径：
  未装走 `scoop install bucket/name`，已装走 `scoop update --force`（含 global 修正、
  `isLocalManifestInstall` 修复分支）。整体行为不变，但该分支的 L342 `--force` 缺陷需先按
  §1.3 修复。
- **未保存（直接安装 / Reinstall 未保存的 manifest）** -> `installFromManifest`：
  - 目标 scope 按 `installedSnapshot()[name]?.isGlobal`（`reinstallGlobal(snapshot, name)` 已有）。
  - 已装：`scoop uninstall <name> [-g]`（无 -p）-> `scoop install <tempFile> [-g]`。
  - 未装：`scoop install <tempFile>`。
- 用户手动改走 Discover 的 bucket update --force 时，`update()` 现有
  `isLocalManifestInstall` 分支（install.json 无 bucket -> uninstall + `scoop install bucket/name`）
  能把"本地直装"的 app 迁移回 bucket 归属——但该分支当前**有缺陷，必须先修**（见 §1.3），
  不能当作无条件救生索。

### 1.2 注意：直接安装会顶掉同名 bucket 安装

先卸后装会让一个"从 bucket 装的正式版"变成"本地直装版"（install.json 丢 bucket）。
这是需求的自然语义（用户拿未发布 manifest 覆盖正式版），Discover 侧依赖 `isLocalManifestInstall`
修复分支（**需先按 §1.3 修复**）兜底：下次从 bucket update 时自动迁移回来。需要在 UI 提示/风险清单里
写明（见 §6），不做弹窗拦截（保持"快速试装"的轻量语义）。优先走 §1.4 的 bucket 改写路径可避免
这种退化。

### 1.3 前置修复项：`update()` 的 `isLocalManifestInstall` 分支

`scooper/service/ScoopClient.kt:326-345` 的 `update()` 修复分支在 `force=true` 时给
`scoop install` 追加 `--force`（L342）：

```kotlin
if (force) installArgs.add("--force")   // L342
```

但 `scoop-install.ps1` 的 getopt 只接受 `g i k s u a:`（`--arch`），未知选项直接
`"scoop install: $err"; exit 1`（`scoop-install.ps1:46`）。即：**直装 -> 保存到 bucket ->
点 Install**（此时 `systemInstalled=true`，走 `update(force=true)`）会命中这条路 ->
命令 exit 1 -> 安装失败。这条路径不是直装的旁支，而是直装后的常规操作，必须在第一期修。

**修复**：去掉 L342 的 `if (force) installArgs.add("--force")`。该分支的重装语义本就由
"先 uninstall 再 install" 保证，`--force` 对 `scoop install` 既非法也多余。修复后需补一条
命令组装断言（`ScoopClientUpdateForceTest` 模板）：本地直装 + force=true 时 install args
不含 `--force`。

因此 §2/§6 中"Discover 靠它自动迁移"的表述需重新定位为"依赖本前置修复"。

### 1.4 与既有 `installVersion` 的取舍

`scooper/service/ScoopClient.kt:404-450` 的 `installVersion(app, manifestFile, global)` 已实现一条
相近管线：已装则 `uninstall -> 安装本地 manifest`；且当**同名 bucket manifest 存在**时，会
临时改写 `bucketsBaseDir/<bucket>/bucket/<name>.json` -> `scoop install <bucket>/<name>` ->
`finally` 恢复原文件（L423-441）。该路径下 install.json 记 bucket、`url=null`，**无悬空引用**。

**为何不直接复用/扩展 `installVersion` 作为直装入口**：
- `installVersion` 的"改写技巧"依赖 `app.bucket != null` **且**同名 bucket manifest 已存在。
  直装的核心场景是**新 app 不在任何 bucket**（或尚未保存），此时 `bucketManifest == null`，
  走到 L445 的 fallback `scoop install <file>`，install.json 即记录 `url=<临时文件>`，悬空引用
  原样复现——即 `installVersion` 在新 app 场景并没有解决本设计要解决的问题。
- `installVersion` 语义面向"装某个已发布版本"（app.name 来自既有 bucket 记录），直装面向
  "装当前编辑器 manifest"（名字来自 `saveResult.appName / suggestedAppName / deriveAppName`，
  app 可能尚无记录），两者输入模型不同。

**可部分复用的点（推荐采纳）**：当**已装且同名 bucket manifest 存在**时（典型：用未发布
manifest 覆盖 main bucket 里的正式版），优先走 `installVersion` 的 bucket 改写路径，
从而**保留 bucket 归属**，避免 install.json 丢 bucket、避免 §6 第 1 条的迁移往返与展示偏差。
即直装的落地策略应为：

```
已装 且 同名 bucket manifest 存在  -> 复用 installVersion 的改写路径（保留 bucket）
否则（新 app / 未保存 / 无同名 bucket manifest） -> installFromManifest（文件直装）
```

这使"覆盖正式版"这一最常见场景不再退化为 orphan，代价是 `installVersion` 的改写技巧需要
被直装路径显式调用（当前它是独立方法，签名 `installVersion(app: App, manifestFile: File, global: Boolean)`
可直接复用，无需改其内部）。

### 1.5 失败与清理路径（必须定义）

临时 manifest 与安装过程需要明确的失败语义，参照 Test Install 的 `finally` 清理
（`CreateViewModel.kt:873-887`）与 `onInstallSaved` 的 `finally { installing=false }`
（`CreateViewModel.kt:955-957`）：

1. **临时 manifest 写盘 IOException**：捕获后 toast 报错、`installing` 复位、**不执行**任何
   scoop 命令、不产生 install.json 副作用。临时文件若已部分写出则尽力删除。
2. **`installFromManifest` 抛异常（非 CommandResult 返回）**：`try/finally` 保证
   `installing=false`；异常映射为 toast（复用 `AiErrorTranslator.maskSecrets`），并标记
   "已卸载但安装失败，可重试"（见 §6 第 2 条）。
3. **uninstall 成功但 install 失败**：当前 `installFromManifest` 应沿用 `installVersion` /
   `testInstallManifest` 的约定——uninstall `exitCode != 0` 时**不执行 install**并返回 uninstall
   结果；install 失败则返回 install 结果，UI 按 §6 第 2 条提示非原子风险。
4. **失败时临时文件是否删除**：**统一删除**（`finally` 中）。保留一个指向 manifest 的失败产物
   没有正面价值，反而扩大悬空引用面。是否需要"保留临时文件以便用户排查"作为可选项，见 §1 "临时文件位置与生命周期"段。
5. **成功路径**：装完后删除临时文件（见 §1 正文），install.json.url 指向已删路径——该悬空引用
   的后果与缓解见 §6 第 4 条（**与 Test Install 不同**：Test Install 会 `cleanupTestInstall` 卸载并
   删临时文件，不留悬空 install.json；直装则永久留下指 URL）。

## 2. 状态模型

| 字段 | 直接安装成功后 | 说明 |
|------|---------------|------|
| `installedForUse` | `true` | 本会话内这台机器上装好了即可用，与是否进 bucket 无关 |
| `installedShortcuts` | `manifestHasShortcuts(activeManifestJson)` | 同现有 |
| `systemInstalled` | `true` | 同现有 |
| `manifestSaved` / `saveResult` | **不变**（保持 false/null） | 关键点：安装不再是"保存的下游"，SaveResultPanel 语义不被污染 |
| 无新字段 | - | "本地直装"不需要专门标记：Discover 靠 install.json 的 bucket=null 判定（运行时事实），VM 无需重复建模 |

**Discover 展示**（无需新代码，核实过链路）：
- `buildAllApps` -> `findOrphanInstalledApps`：不在任何 bucket 里但 `current/manifest.json`
  存在的已装 app 会以 `bucket=null`、`status=INSTALLED` 入库；`upsertApps` 有 orphan
  分支（`Apps.bucketId eq null` 查找）；`loadAllApps` 的 stale 清理对 INSTALLED 记录
  只删非 INSTALLED 的。列表里显示为普通已装 app，无 bucket 标签。
- **例外**：若同名 app 在某 bucket 中存在 manifest（常见：覆盖安装了 main bucket 里的
  正式版），`buildAppsFromManifest` 会按 bucket manifest 而非 installed 状态显示它——
  显示的是"bucket 版本"，但本机实际装的是本地直装版。可接受的展示偏差，风险清单记录。
- `refreshInstalledAppRecordCore(bucket, name)` 不适用（它按 bucketDir 解析 manifest）。
  直接安装成功后改为 upsert 一个 orphan 记录。**必须用复数 `AppsRepository.upsertApps`
  （`AppsRepository.kt:328-345`）**，而不是单数 `upsertApp`：
  - `upsertApps`（复数，列表批处理）在 `bkt == null` 时用严格 orphan 查询
    `(Apps.name eq app.name) and (Apps.bucketId eq null)`，只命中真正的孤儿行。
  - `upsertApp`（单数，`AppsRepository.kt:405-425`）在 `bkt == null` 时**不加 bucketId 过滤**
    （`if (bkt != null)` 才 andWhere），会命中同名 bucket 行并 `existing.update(app, null)`，
    **把该行的 bucketId 清空**——副作用：同名 bucket 行瞬时变孤儿，直到下次全量 sync 重建。
  因此若单数方法是唯一公开入口，需要**新暴露一个复数（或带严格 orphan 语义）的公开方法**，
  或在 `upsertApp` 内补 orphan 分支。推荐直接复用/公开 `upsertApps`。
  推荐做法：走 upsert（Discover 立即可见 + `scoopService?.notifyInstalled` 事件照发），
  复用 `ScoopClient.buildAppFromJson` 语义（从当前 manifest 解析 name/version/description）。
  或者更简单：直接安装路径不刷新索引，依赖 `installedSnapshot`/下次全量 sync（代价是 Discover
  不能立即看到新记录）。
- **已知缺口（[S4]）**：孤儿记录（`bucket=null`）在 Discover 上的 **Update 按钮静默无效**——
  `update()` 需要 `app.bucket`，orphan 没有 bucket，命令无法正确组装。`buildAppFromJson`
  （`ScoopClient.kt:187-206`）为 orphan 生成的 App 仍会带 `status=INSTALLED`，UI 会渲染
  Update 按钮。本期不做处理，但需在风险清单记录：孤儿 Update 要么禁用、要么走
  `installFromManifest` 重装语义。

## 3. UI / 按钮改动点

`CreateScreen.kt` ManifestActions 区（约 L2407-2440）+ `ExecutionModels.kt` 纯函数：

1. **Install 按钮与 Save to Bucket 并存，不再依赖 manifestSaved**：

```kotlin
// ExecutionModels.kt —— 新的 enabled 纯函数（替换/重载 installActionEnabled）
internal fun installActionEnabled(
    manifestJson: String,        // 校验用 isScoopManifestJson（合法 JSON object），不用 isNotBlank
    installedForUse: Boolean,
    running: Boolean, testing: Boolean, installing: Boolean,
): Boolean = isScoopManifestJson(manifestJson) && !installedForUse && !running && !testing && !installing
```

   - **enabled 校验复用既有能力**：`isScoopManifestJson`（或直接复用按钮区现成的
     `state.manifestInvalidJson`，见 `CreateScreen.kt:2410-2419` 的 `invalidJson` 接线），
     不用 `isNotBlank()`——空白之外，非法 JSON、非 object 也应禁用安装。

   - `manifestSaved`/`hasSaveResult` 从条件中移除；`saveResult` 不再是安装前置。
   - `installActionLabel(systemInstalled)` 保留：systemInstalled=true 时显示 "Reinstall"
     （未保存时语义 = "把当前编辑的 manifest 重装到机器上"，成立）。
   - `installActionVisible(!installedForUse)` 保留；`openAppActionVisible`、Open 按钮不动。
   - `installActionPrimary` 保留 bucket 路径的 primary 规则；直接安装路径建议不抢 primary
     （Test Install 通过后 Save to Bucket 仍是 primary，鼓励最终落 bucket）。
2. **onInstallSaved 守卫改动**（`CreateViewModel.kt` L909-964）：

```kotlin
val manifestJson = state.activeManifestJson
if (manifestJson.isBlank()) { toast("No manifest to install"); return }
val snapshot = scoopClient.installedSnapshot()
val name = <resolveAppName>()                     // 真实 app 名
val systemInstalled = snapshot.containsKey(name.lowercase())
if (state.manifestSaved && state.saveResult != null) {
    // 现有 bucket 分支原样保留（install bucket/name 或 update --force）
} else {
    // 新：installFromManifest(name, tempFile, scope = reinstallGlobal(snapshot, name))
}
// 成功后：installedForUse/installedShortcuts/systemInstalled = true（两分支相同）
// 索引刷新：bucket 分支走 refreshInstalledAppRecord；本地分支走 orphan upsert（§2）
```

   名字解析链（**不存在 `manifest.name` 回落**——生成的 manifest 无顶层 `name` 字段，
   `ReferenceManifests.kt` 中 `"name"` 出现 0 次；`rewriteManifestName` 的
   `if (!obj.containsKey("name")) return raw` 即反证）：
   `saveResult.appName`（已保存时）-> `suggestedAppName` ->
   `GithubUrlParser.deriveAppName(lastUrl)` -> `APP_NAME_REGEX` 校验 -> 校验失败则 toast 报错。
   对应既有实现：`CreateViewModel.kt:2241`（`nameArg ?: suggestedAppName`）与
   `CreateViewModel.kt:1570-1573` / `ScoopAiBridge.kt:587-590`（deriveAppName 来源）。
   临时文件名必须与解析出的 app 名一致（scoop 以文件名定名）。
   已装同名时的确认/提示：不阻止安装，toast 说明"将覆盖已安装版本"即可。

## 4. AI install_app 工具（scoop-ai 引擎侧 + scooper 宿主侧）

当前约束链：`InstallAppArgs(bucket, name)` 两参数必填 -> prompt 明确
"Precondition: manifest must already be saved... call save_to_bucket first, then install"
（`PhasePrompts.kt` L797-800）-> 宿主 `executeInstallApp(bucket, name)`。

设计目标：用户对 AI 说"直接装，先别存 bucket"时模型能照做。改动跨两仓：

- **scoop-ai（引擎）**：
  1. `InstallAppArgs` 改为 `bucket: String? = null, name: String`（bucket 可空 = 未保存直装；
     若模型需要显式表达"装当前编辑器里的 manifest"，可加 `source: enum(bucket, current)`，
     首版用 bucket=null 约定即可）。
  2. 工具 description 与 `buildAssistPrompt`/`buildRepairPrompt` 的 Install App 段改写：
     "install_app 可在未保存时安装当前 manifest（bucket 省略，直接从本地 manifest 安装）；
     已保存时传 bucket+name 走 bucket 安装"。
  3. handler 签名 `installHandler: (suspend (bucket: String?, name: String) -> String)?`
     （跨宿主边界，向后兼容需两仓同步升级）。
- **scooper（宿主）**：`executeInstallApp(bucket, name)` 中 bucket 为空/blank ->
  走与 §3 相同的 `installFromManifest` 本地分支（名字解析、orphan upsert、事件通知都共用）；
  非空 bucket -> 现有逻辑。`aiInstallApp` 的 result JSON 增加 `"mode": "local"|"bucket"`
  让模型如实报告。
- **工作量提示（[Q1]）**：这条要动 scoop-ai 的工具 schema + prompt + 桥接签名，以及 scooper 的
  bridge 类型；属于跨仓 API 变更。**二期裁剪是有意决定**，但需明确记录：
  - "用户让 AI 直接装、先别存 bucket"是**核心使用场景之一**（用户对 AI 下自然语言指令即可完成，
    比手动点按钮更贴合 agent 产品定位），不是边缘需求。
  - 二期成本：两仓同步升级（schema/prompt/handler 签名/bridge 类型）+ 兼容性（旧桥不认 bucket=null）。
  - 触发条件：第一期 UI 直装上线并稳定后，或用户明确要求 AI 直装时启动。
  - 风险：跨仓 API 变更要求两仓版本匹配，未同步升级会出现 handler 签名不匹配；需在 bridge 层做
    防御（bucket 参数缺失/类型不符时回退到现有 bucket 路径或报错）。

## 5. 测试点（现有基建可覆盖）

现有 shim：`scoopTestEnv()`（`util/Process.kt`）+ `TEST_SCOOP_SHIM_ENV` 把 `scoop` 指到
stub `.cmd`；`CreateViewModelInstallEventTest.withScoopShim` 是可抄的模板；
`ScoopClientUpdateForceTest` 是命令组装断言的模板。

- **ScoopClient 层（新增 `ScoopClientInstallFromManifestTest`）**
  - [S2] 参数断言走**纯函数** `installFromManifestArgs(...)`（对称 `uninstallArgs`/`updateArgs`，
    模板 `ScoopClient.kt:352-359`）：未装 == `["scoop","install",<tempFile>]`；已装 local 的
    uninstall == `["scoop","uninstall",<name>]`；已装 global 两者都带 `sudo ... -g`。
    **不要依赖 shim 捕获参数**：`CreateViewModelInstallEventTest.kt:66-71` 的 shim 只是固定
    `.cmd`，不记录参数。
  - 序列行为（uninstall 失败 exit!=0 时不执行 install、返回 uninstall 结果）用 shim 的行为断言
    （副作用/退出码），不依赖参数记录。
  - 缓存预热：`prewarmTestInstallCache(真实app名, tempFile)` 被调用（复用
    `ScoopClientPrewarmCacheTest` 的断言方式）
  - install 输出经 `evaluateInstallOutput` 分类（shortcut 失败行强制 exitCode 1）
  - [I6] 失败/清理：临时 manifest 写盘 IOException -> 不产生 scoop 命令；install 抛异常 ->
    `installing` 复位且临时文件被删（`finally` 清理断言）
- **CreateViewModel 层**
  - `onInstallSaved` 未保存 + manifestJson 非空：不再 toast "Save the manifest first"，
    走本地安装且成功后 `installedForUse=true`、`manifestSaved=false`、`saveResult=null`
  - `onInstallSaved` 已保存：行为与现状完全一致（回归，复用
    `CreateViewModelInstallGuardTest` / `SystemInstalledTest` / `InstallEventTest` 用例改写）
  - systemInstalled=true 且未保存：走 uninstall+install（Reinstall 本地版）
  - manifestJson 空白/非法：toast，无命令执行
- **ExecutionModels 纯函数**
  - 新 `installActionEnabled`：非法/空白 manifest false（基于 `isScoopManifestJson`）;
    manifestSaved=false 但 manifest 合法 true；installedForUse=true false；
    running/testing/installing 任一 true false
  - `installActionLabel(true)=="Reinstall"` 保留回归
- **名字解析（[I2]）**
  - `saveResult.appName` 优先 -> `suggestedAppName` -> `GithubUrlParser.deriveAppName(lastUrl)`
    -> `APP_NAME_REGEX` 校验失败 toast；断言**不读 `manifest.name`**（生成 manifest 无该字段）
  - 临时文件名 == 解析出的 app 名（scoop 以文件名定名），不一致不执行安装
- **orphan upsert（[I3]）**：成功后 `repository.getApps()` 出现 bucket=null 的 INSTALLED 记录，
  且**同名 bucket 行的 bucketId 不被清空**（用复数 `upsertApps`；用单数 `upsertApp` 的
  回归用例应断言其会清 bucketId，作为反例）
- **手测**：ripgrep 等已验证包走"生成 -> 直接安装 -> scoop uninstall -> 保存 -> bucket 安装"，
  以及"先 bucket 安装 -> 改 manifest -> 直接安装覆盖 -> Discover 显示 -> 从 bucket update --force 迁移回"。

## 6. 风险清单

1. **覆盖同名正式安装**：直接安装会把 bucket 正式版换成未发布版（install.json 丢 bucket）。
   迁移依赖 `update()` 的 `isLocalManifestInstall` 分支，**该分支当前有缺陷（§1.3，L342 `--force`），
   直装后点 Install 会 exit 1——必须作为前置修复项**。优先走 §1.4 的 bucket 改写路径可避免退化。
   用户若只看 Discover 的 bucket 版本号可能误解实际装的版本（展示偏差，见 §2）。
2. **先卸后装非原子**：uninstall 成功但 install 失败 -> 机器上没有这个 app 了。
   缓解：预热缓存降低 install 失败率；失败 toast 明确"已卸载但安装失败，可重试"。
   不做"装到临时名再 rename"类花活（scoop 无 rename，复杂度不值）。
3. **persist 数据**：uninstall 不带 `-p`，`persist/<app>` 保留；若新旧 manifest 的 persist
   字段不一致，scoop 重装时会按新 manifest 重建/忽略，与原生 update 行为等价。
4. **临时文件生命周期（与 Test Install 不同，[I5]）**：直装成功后删除临时文件，install.json.url
   永久指向已删路径。用户手动 `scoop update <name>`（无 `--force`）**不会报错**：`url_manifest`
   返回 null -> `app_status` 令 `removed=true`、`latest_version`=已装版本 -> `outdated=false` ->
   `scoop-update.ps1:432` 条件为假，落到 L442 打印 `"<name>: <ver> (latest version)"`（绿色、
   误导，实际什么都没做）。`No manifest available for '<name>'.`（`scoop-update.ps1:290`）
   只在 `--force`/FORCE_UPDATE 时到达。
   **这不是"与 Test Install 相同的限制"**：Test Install 结束会 `cleanupTestInstall`
   （`CreateViewModel.kt:1701-1713`）卸载并删临时文件，**不留**悬空 install.json；直装则永久留下
   指向已删文件的 url，两者后果不同。缓解：临时文件放稳定目录（如
   `%LOCALAPPDATA%\scooper\direct-install\<appName>.json`）而非 `%TEMP%`，或采用 §1.4 的
   bucket 改写方案（install.json 记 bucket、url=null，无悬空引用）。
5. **app 名解析歧义**：回落链应为 `saveResult.appName -> suggestedAppName ->
   GithubUrlParser.deriveAppName(lastUrl) -> APP_NAME_REGEX 校验 -> toast`（**不存在 `manifest.name`
   回落**，生成的 manifest 无该字段），要在实现里钉死，否则临时文件名与 scoop 注册名不一致会造成
   Discover 记录对不上。
6. **AI 路径跨仓变更**：InstallAppArgs schema 变更是 scoop-ai/scooper 两仓联动，
   prompt 也要同步（否则模型不知道可以省 bucket）。**二期裁剪为有意决定**，Q1 相关成本/触发
   条件见 §4。
7. **global 安装**：`scoop uninstall -g / install -g` 需要管理员权限（sudo 提权弹窗），
   直接安装按钮触发 global 重装时要有预期（现有 bucket 路径同样问题，非新增风险）。
8. **Discover 孤儿 Update 按钮静默无效（[S4]）**：orphan（bucket=null）记录的 Update 按钮
   无法正确组装 `scoop update` 命令，点击无实际效果。`buildAppFromJson`
   （`ScoopClient.kt:187-206`）仍为 orphan 生成 `status=INSTALLED` 的 App，UI 会渲染按钮。
   本期不处理，记录为已知缺口（见 §2）。
9. **并发（[S3]）**：同名 app 的 uninstall+install 非原子序列需在客户端层串行/互斥（§1）。
10. **orphan upsert 用错方法（[I3]）**：用单数 `upsertApp` 会命中同名 bucket 行并清空其
    bucketId（瞬时变孤儿，直到下次全量 sync）。必须用复数 `upsertApps`（§2）。

## 7. 实施步骤与改动文件清单

实施步骤（有序）：
0. **【前置修复项，必做】** `scooper/service/ScoopClient.kt:342` 去掉 `update()` 的
   `isLocalManifestInstall` 修复分支里 `if (force) installArgs.add("--force")`；补参数组装回归
   （`ScoopClientUpdateForceTest`）。理由见 §1.3（scoop install 不支持 `--force`，直装后点
   Install 会 exit 1）。
1. `ScoopClient` 新增 `installFromManifest` + `installFromManifestArgs`（纯函数）+ 并发防护（§1、§1.5）。
2. `CreateViewModel.onInstallSaved` 分支与名字解析链（§3），失败/清理语义（§1.5）。
3. orphan upsert 用 `upsertApps`（必要时暴露公开方法，§2）。
4. `ExecutionModels.installActionEnabled` 校验改用 `isScoopManifestJson`；`CreateScreen` 接线。
5. 补测试（§5）与手测（§5 末尾）。
6. 视情况采纳 §1.4 的 bucket 改写优化（已装且同名 bucket manifest 存在时保留 bucket 归属）。

scooper（第一期）：
- `src/main/kotlin/scooper/service/ScoopClient.kt` — **前置修复（L342 去 `--force`）** +
  新增 `installFromManifest`/`installFromManifestArgs`（uninstall+install、预热、
  `evaluateInstallOutput`、并发防护）
- `src/main/kotlin/scooper/service/ScoopCli.kt` — 新增 `installFromManifest` 接口
- `src/main/kotlin/scooper/repository/AppsRepository.kt` — 确保 orphan upsert 走复数
  `upsertApps`（或暴露公开方法，§2/[I3]）
- `src/main/kotlin/scooper/viewmodels/CreateViewModel.kt` — `onInstallSaved` 守卫与分支、
  名字解析链、失败/清理、orphan upsert 辅助
- `src/main/kotlin/scooper/viewmodels/ExecutionModels.kt` — `installActionEnabled` 新签名（保留旧函数或一并迁移）
- `src/main/kotlin/scooper/ui/create/CreateScreen.kt` — 按钮参数改接线
- 测试：`ScoopClientInstallFromManifestTest`（新）、`ScoopClientUpdateForceTest`（前置修复回归）、
  `CreateViewModelInstallGuardTest`、`CreateViewModelSystemInstalledTest`、
  `CreateViewModelInstallEventTest`、`CreateViewModelExecutionTest`（ExecutionModels 纯函数）

scoop-ai（第二期，AI 直装，详见 §4 [Q1]）：
- `core/.../agent/InstallAppTool.kt` — args schema
- `core/.../agent/prompt/PhasePrompts.kt` — Install App 段
- `core/.../ScoopManifestGenerator.kt` — handler 签名
- scooper `service/ScoopAiBridge.kt` + `CreateViewModel.executeInstallApp` — 桥接
