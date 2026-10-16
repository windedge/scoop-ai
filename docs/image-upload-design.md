# 会话图片上传与识别 - 影响面分析与设计方案

只读调查结论 + 分阶段实施设计。范围：scooper（Compose Desktop UI）+ scoop-ai 引擎（Koog 1.1.1）。

## 1. 调查结论

### 1.1 Koog 引擎多模态能力（版本 1.1.1，已确证）

- **Message.User 原生支持多 part**：`Message.User(parts: List<MessagePart.RequestPart>)`，parts 可含 `MessagePart.Text` 与 `MessagePart.Attachment(source, ...)`（`prompt/prompt-model/.../Message.kt:119`）。
- **图片附件模型**：`AttachmentSource.Image(content: AttachmentContent, format, mimeType, fileName)`，content 支持 `AttachmentContent.Binary`（字节数组）与 `AttachmentContent.URL`（`Attachment.kt:132`）。`AttachmentContent.Binary.asBase64()` 现成可用。**注意：`Image` 的 init 禁止 PlainText content。**
- **openai-compatible 编码已内置**：`AbstractOpenAILLMClient.toOpenAIContentPart()`（`AbstractOpenAILLMClient.kt:344-368`）把 `AttachmentSource.Image` 编成 `{"type":"image_url","image_url":{"url":"data:<mime>;base64,..."}}`，URL content 直接传 URL。Anthropic client 也有对应 `AnthropicContent.Image`。**无需自己写编码。**
- **关键 gate：capability 检查**。openai client 在编码图片 part 前调用 `model.requireCapability(LLMCapability.Vision.Image)`，能力缺失直接抛 `LLMClientException`。而本项目 `openAICompatibleCapabilities()`（`LlmProviderFactory.kt:194`）当前能力集不含 Vision.Image。
- **assist 子图入口是 `defineTask: suspend (Input) -> String`**（`AIAgentSubgraphExt.kt:214`）：子图把返回的 String 作为 user 消息发给 LLM。要让图片进消息，子图输入或输出层面需要能携带附件 —— Koog 的 subgraphWithTask 只接受 String task 文本，**图片 part 无法通过 defineTask 直接进入**，需绕道（见 3.4 方案）。

### 1.2 模型前提

amd/deepseek-v4-flash 经网关路由，**无法确证是否多模态**。即使把 Vision.Image 加进能力声明，网关/后端仍可能拒绝（400）或忽略图片。设计上做两层兜底：
1. 能力声明层：不盲目全局加 Vision；assist 会话带图时对该 model 实例附加 `LLMCapability.Vision.Image`。
2. 运行时失败兜底：捕获 LLMClientException / 4xx，向用户回一条"当前模型可能不支持图片，请换多模态模型或用文字描述"的错误气泡（复用 AiErrorAction 机制）。

### 1.3 scooper UI 现状

- `CreateViewModel.ChatMessage`（`CreateViewModel.kt:62`）纯文本；渲染在 CreateScreen ~L1431。
- Composer：`CreateScreen.kt:2814` 附近 `inputText` 状态 + ~L2861 BasicTextField；发送走 `onSendText` -> `startAssistInternal(instruction)`（`CreateViewModel.kt:1305`）-> `scoopAiBridge.startAssist(...)`（`ScoopAiBridge.kt:311`）纯 String 链。
- 入口形态：
  - 粘贴：Compose Desktop 的 `BasicTextField` 对图片粘贴不产出文本；需在 Composer 外层挂 AWT `KeyboardFocusManager`/`TransferHandler` 或对 `LocalClipboard` 轮询判断 `DataFlavor.imageFlavor`，取 `Transferable -> Image -> 编码 PNG 字节`。
  - 附件按钮：Compose Desktop 无原生 file picker，用 AWT `FileDialog`（或 `JFileChooser`）过滤 png/jpg/webp/gif。

### 1.4 传输编码与尺寸

- 请求体走 `data:image/png;base64,...`，base64 膨胀 4/3。截图 PNG 100KB-2MB -> base64 130KB-2.7MB，单请求可接受，但会进对话 history 多轮重复发送。
- 建议：UI 侧入队前压缩（长边限 1568px，PNG 截图转 JPEG/WEBP q~85 或保留 PNG 若含文字），单图上限 4MB（压缩后），单条消息最多 3 图。引擎侧不缓存原图进 checkpoint（见风险）。

## 2. 分阶段实施计划

### 阶段 1：scooper UI 选图/粘贴（本地能力，可独立交付）

**落点**
- `CreateViewModel.kt`：
  - `CreateState` 增加 `pendingImages: List<PendingImage>`（`data class PendingImage(val id, val bytes: ByteArray, val mime: String, val preview: ImageBitmap)`）。
  - 新 intent：`fun attachImages(bytes: List<ByteArray>, mime)` / `fun removePendingImage(id)`；`post`（现有 send intent）在 `pendingImages` 非空时把它们随文本一起进 `startAssistInternal`。
  - `ChatMessage` 增加 `images: List<ChatImage>`（`data class ChatImage(val bytes, val mime)`，仅内存，UI 渲染缩略图）。
- `CreateScreen.kt`：
  - Composer 工具栏加附件按钮（AWT FileDialog，多选，过滤扩展名）。
  - Composer 容器挂全局粘贴监听（AWT `KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher` 或 Component 层 `TransferHandler`），检测 `imageFlavor` 后 `attachImages`。
  - Composer 上方 pending 缩略图行 + 删除角标；消息气泡内 `Image(bitmap)` 缩略图（等宽块渲染旁边）。
- 压缩工具：新增 `ImageCodec`（JVM `ImageIO` + `Graphics2D` 缩放，PNG->JPEG/WEBP）。

**API 变更签名（scooper 内部）**
```
CreateState(..., val pendingImages: List<PendingImage> = emptyList())
fun attachImages(images: List<PendingImage>)
fun removePendingImage(id: String)
ChatMessage(..., val images: List<ChatImage> = emptyList())
```

### 阶段 2：bridge 扩展（scooper -> 引擎调用面）

**落点**：`ScoopAiBridge.kt`
```
fun startAssist(
    ...,
    images: List<ScoopAiImage> = emptyList(),   // data class ScoopAiImage(val bytes: ByteArray, val mime: String)
    ...
)
```
透传给 `generator.assist(..., images = images)`。图片不进 checkpoint 服务（仅本次请求内存态）。

### 阶段 3：引擎 Message 多 part（scoop-ai）

**核心问题**：subgraphWithTask 的 defineTask 只能产 String。方案（推荐 A）：

**A. 闭包注入附件（改动最小）**
- `AssistContext` 不动（保持 @Serializable 纯文本，checkpoint 兼容）。
- `ScoopManifestStrategy.buildStrategy` 增加可选参数 `assistAttachments: (suspend (AssistContext) -> List<AttachmentSource.Image>)?`（或非 suspend `() -> List<...>`）。
- 子图内部：`subgraphWithTask` 之后在 assist 子图内加一个 `node("withImages")` 或改用自定义 subgraph：
  - 最小实现：在 defineTask 返回 String 后 Koog 自己构造 `Message.User(text)`；图片进不去。因此实际做法是**放弃对 assist 子图用 subgraphWithTask 的 String 入口**，改为在 assist 子图内手写节点：`node(nodeStart -> nodePrepareImageMessage)` 产出 `Message.User(listOf(Text(task), Attachment(Image(binary))), ...)` 再接 nodeLLMRequest，finish 环沿用 LenientStringFinishTool 逻辑。参照 AGENTS.md 中 subgraph DSL 模式（`nodeLLMRequest()` 输入 `Message.User`，配合 `asUserMessage`）。
- `ScoopManifestGenerator.assist(...)` 签名：
```
suspend fun assist(
    manifestJson: String,
    instruction: String,
    history: List<String> = emptyList(),
    images: List<BinaryImage> = emptyList(),   // 引擎侧轻量包装 (bytes, mime/format)
    ...
)
```
  images 只在 `explanatory=true` 的首轮 assist 注入；内部 repair 轮与 history 重放不携带（节省 token、避免 repair 轮触发 capability gate）。
- `buildAssistPrompt`（PhasePrompts.kt:649）不动；有图时在 prompt 末尾由 strategy 追加一句"[N 张用户截图已附在本消息中]"占位文本，帮助模型意识到附件存在。

**B. 备选：LLMParams.attachment / history 注入**（Koog 若有 per-request attachment API）——1.1.1 无现成入口，放弃。

**capability**
- `LlmProviderFactory`：不动全局 `openAICompatibleCapabilities()`；在 `ScoopManifestGenerator` 构建 assist 会话模型时，若 `images` 非空，`model.copy(capabilities = model.capabilities + LLMCapability.Vision.Image)`。
- 运行时兜底：bridge 的 onFinished 失败路径识别 `LLMClientException` 含 "Vision"/"capability" 或 HTTP 4xx 含 image 相关错误，返回带 `AiErrorAction.SwitchModel` 的错误。

### 阶段 4：编码验证（openai-compatible 链路）

- Koog 已实现 base64 data URL 编码（1.1 已确证），本阶段只做：
  - 拦截器链回归：确认 `InterceptingHttpClient` / `ResponseInterceptors`（`LlmProviderFactory.kt`）不破坏含 image_url part 的请求体（现有拦截器都做 JSON 重写，需逐个过一遍 `stripToolChoice` / `malformedFunctionCallRetryHint` / `normalizeMissingCreated` 对大请求体的性能影响——base64 字符串可能 MB 级，正则/JSON 重新序列化要确认无 O(n^2)）。
  - 端到端：跑一次真实带图 assist。

## 3. 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| 模型/网关不支持图片 | 400 或图片被静默忽略 | 运行时错误气泡 + "换模型"建议；不预探测 capability |
| checkpoint/resume 序列化图片 | `SessionRequest.Assist` 是 @Serializable，加 bytes 字段会让 checkpoint 膨胀且跨版本不兼容 | 图片**不进** SessionRequest/AssistContext/checkpoint，仅闭包透传；resume 后重放纯文本 |
| 超大图 | 请求体 MB 级、网关拒绝、token 爆炸 | UI 侧压缩 + 4MB/3 张上限；图片只在首轮发送不进 history 重放 |
| 拦截器对大 base64 体的性能/破坏 | 请求重写慢或损坏 | 阶段 4 专项回归测试 |
| AWT FileDialog/剪贴板在 Compose Desktop 的线程模型 | 粘贴监听抖动、FileDialog 阻塞 UI 线程 | FileDialog 在独立 Thread；粘贴用 KeyEventDispatcher 并 post 到 UI 线程 |
| subgraph 改造回归（assist 子图从 subgraphWithTask 改手写节点） | 现有 assist/repair 行为回归 | 先写 ThinkingCompatibleStrategyMockTest 式 mock 测试锁行为再改 |

## 4. MVP 边界建议

MVP（第一个可交付）：
1. 仅 assist 会话支持图片（generate 三阶段不带图）。
2. 仅附件按钮选图（粘贴可后补）；PNG/JPEG；单图；长边 1568 压缩。
3. 图片只在当轮 assist 首次 LLM 调用出现，不进 history/checkpoint。
4. 失败兜底：错误气泡提示换模型。

明确排除（后续迭代）：粘贴入口、多图、图片进 checkpoint/resume、generate 阶段读图（如从截图提取版本号）、Anthropic 专属 cache_control 优化。
