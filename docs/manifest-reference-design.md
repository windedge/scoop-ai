# 设计：Create 引用已有 manifest（@bucket/app）

来源：grilling 会话逐题确认（Q1-Q12）。本文件是共识总表，实施以本文件为准。

## 语义总纲

`@bucket/app` 是**引用标识**：随用户消息原样进入对话文本，激活"读取引用 manifest"的工具；
被引用的 manifest 可能是修改对象，也可能只是参考（如"照 @main/golang 的写法给 X 生成"）——
**绝不直接写入 `state.manifestJson`**（manifestJson 只装本会话产出物：URL 生成的或模型新写的）。

## 决策表

| # | 决策 |
|---|---|
| 入口 | 同一输入框；`@bucket/app` 显式语法与 URL/普通文本共存 |
| 首轮行为 | `@ref` 原样进文本、不剥离；宿主不预读 manifest 内容、不校验存在性；模型调工具，typo 时拿失败 JSON 自行转告用户 |
| 工具 | `read_manifest_reference(bucket, name)`：两参数均必填；返回 manifest JSON 原文 + 顶部元信息（bucket/name/文件路径）；失败返回 `{"success":false,"error":"..."}`（与 install_app 风格一致，不抛异常） |
| 工具注册 | 会话级常驻：宿主 `startAssist` 传 `onReadManifestRef` handler（照 install_app/save_to_bucket 的 host-backed 管线），本会话后续每轮 assist 都注册；无引用的会话不注册；引擎发版 0.1.10 |
| 分流（核心放宽） | **URL 输入 -> generate；其余一切输入 -> assist（对话）**，不再要求 manifestJson 非空。后果已接受：首条消息可纯对话（AI 反问 URL/用 @ 引用）；打错的 URL 不再即时报错而是当对话文本；合法 URL 总是触发生成 |
| 空 manifest assist | 依赖已有 text-only answer 逃逸（manifest blank + explanation 非空 -> 纯回答）；引擎需验证/修复 verify 对空 manifest、repair 空 基线的边角 |
| UI 输入（C） | 输入框打 `@` 弹 bucket/app 自动补全菜单（数据来自 Discover 的 app 索引），选中插入 `@bucket/app` 后继续编辑，回车发送 |
| UI 气泡（B） | 气泡内 `@bucket/app` 渲染为高亮 chip；点击暂不弹 manifest 预览（仅视觉） |
| 保存（引用感知） | 产出 manifest 的 app 名 == 某引用的 name -> 保存对话框锁定默认 bucket/name（仍可改），overwrite 确认流程不变；名字不匹配（新名/多引用）-> 不锁定，走 lastBucket 记忆 |
| 保存判定规则 | 仅名字匹配（不用模型自报来源，不加协议字段） |
| 引用追踪 | 不存 state：需要时扫描 **User 角色消息**提取 `@bucket/app`（AI 提到的不算）；工具注册判定与保存锁定共用此源 |
| placeholder | 空态改 "Ask anything, paste a GitHub URL, or reference @bucket/app" |

## 现状事实（explorer 已核实）

- `isAssistRequest()`（CreateViewModel.kt:3026）当前要求 manifestJson 非空才走 assist——需放宽
- 引擎 `SessionRequest.Assist(currentManifest, instruction, history, ...)` 不含 URL，`routeRequest`
  对 Assist 直接构造首轮 AssistContext（ScoopManifestStrategy.kt:213-244），凭空第一轮天然支持
- host 工具模式：`ScoopAiBridge.startAssist(onSaveToBucket/onInstallApp/...)` ->
  `ScoopManifestGenerator` 构造器 handler -> assist 工具集条件注册（assistOnly 时）
- `scoopClient.getManifestContent(app)` / `getManifestFile(app)`（ScoopClient.kt:895-898）现成可用
- 保存链路：`aiSaveToBucket`/`saveManifestCore` 与 manifest 来源无关；overwrite 确认已有

## 实施批次

1. **引擎**：`read_manifest_reference` 工具（Args 序列化 + handler 管线 + prompt 条目）；
   空 manifest assist 边角验证与修复；0.1.10 + publishToMavenLocal
2. **宿主分流**：`isAssistRequest` 放宽（URL -> generate，其余 -> assist）；placeholder 文案；
   User 消息 `@bucket/app` 扫描提取（纯函数 + 测试）
3. **宿主 UI**：`@` 自动补全菜单（Compose 输入框 + app 索引过滤 + 键盘导航）；
   气泡 chip 渲染（消息文本分段渲染 `@bucket/app`）
4. **宿主保存**：引用感知默认锁定（名字匹配 -> SaveDialog 预填 bucket/name）

## 遗留小项（实现时定）

- `@` 语法正则：`@bucket/app` 形态、大小写不敏感、排除邮箱等误判
- 补全菜单过滤规则与键盘导航细节
- chip 视觉规格
