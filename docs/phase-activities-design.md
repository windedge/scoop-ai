# 设计：阶段 activities（中间步骤修改原因说明）

来源：grilling 会话（Q1=A，Q2=A2+B1，Bug1=b，Bug2=修）。目标：Create steps 里每个
generate/assist/repair 轮显示"改了什么、为什么"的 bullet 列表，零额外 LLM 调用。

## 决策表

| # | 决策 |
|---|---|
| 数据源 | 模型 finalize 时顺带产出 `activities: List<String>`；prompt 要求"每条一句：改了什么字段、为什么" |
| 阶段范围 | generate + assist + repair（research/analyze 已有 summary 机制，不在本期） |
| 显示 | activities 平铺为 steps 内 bullet（与 "Generated manifest..." 同款） |
| 兜底 | 缺失/空/解析失败 -> fallback 现有模板句；formatRevisionSummary 解析前 strip code fence |
| 附带修 | "attempt N/3" 改 "attempt N"（消除硬编码分母漂移） |
| 传输 | 复用 onPhaseOutput：generate/assist/repair 转发点注入顶层 `_meta.activities`（宿主读完即弃，不进 manifest 展示/保存） |
| 版本 | 引擎 0.1.10 -> 0.1.11 + publishToMavenLocal；scooper 升依赖 |

## 实现要点（explorer 已核实的事实）

- 引擎：`FinalizePayload` 加可选 `activities`（LenientStringFinishTool descriptor 加
  List(String) 参数 + wrapPayload/extractPayload 容错）；strategy 转发点（约 :485 generate、
  :504 assist/repair）解析 manifest 注入 `_meta`（helper `withPhaseMeta`，解析失败原样透传）；
  prompt：buildAssistKernel（PhasePrompts.kt:745-752）为 repair 轮补 activities 指引、
  generate Finalize 段同步
- 宿主：PhaseSummaryFormatter.formatPhaseOutput 优先读 `root["_meta"]["activities"]`
  （兼容 array/string 形态），缺失 fallback 现有模板；:25 "attempt $count/3" 改 "attempt N"；
  formatRevisionSummary（:113）解析前 strip code fence（修 repair bullet 缺失首因）
- ExecutionStep 的 summary/setPhaseSummary 链路不变；UI bullet 渲染已有，无需改
- 兼容性：旧宿主接新引擎（_meta 键被忽略）；新宿主接旧引擎（无 _meta -> fallback 模板）；
  旧 checkpoint 无 activities 解码为 null；引擎对 research/analyze 转发是序列化透传不做二次反序列化

## 测试点

- 引擎：FinalizePayload activities round-trip/缺省/非数组容错；转发点注入 _meta/activities
  为 null 不注入；normalize/verify 收到干净 manifest；prompt 断言
- 宿主：PhaseSummaryFormatter _meta.activities 优先、缺失 fallback、code fence strip、
  "attempt N" 无分母；旧引擎输出回归
