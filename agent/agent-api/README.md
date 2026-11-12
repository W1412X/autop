# agent-api

- 类型：API（纯 Kotlin/JVM）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.agent`
- 职责（docs/tech/01 第 3 节，docs/tech/04）：`Tool`/`ScreenActionTool`/`ToolSpec` DSL、`ToolContext`、
  `ThreadEvent`/`EventPayload`（payload 带 `v`）、`ThreadStore`（`observeFrom` 先回放后实时订阅）、
  `AgentSession`/`RunHandle`/`RunState`/`AgentEvent`（含 `SampleRetrying`）、`ScreenDelta`/`ScreenDeltaKind`/
  `ScreenDeltaReasons`（已停用，tool_optimize D1，只为读取旧事件保留）、`RuntimeState`（`RuntimeStateSource`/`StateEntry`/`StateRequest`）、
  `protocol/`（`HistorySanitizer`/`RequestBuilder`/`JsonToolProtocol`）、`Replay`、扩展点接口（`ContextContributor`、
  `HistoryRenderRule`、`Compactor`、`ToolResultEnricher`、`ThreadEventIndexer`/`ThreadRecallRetriever`、
  `RuntimeStateSource`）、`AgentErrorCodes`。
- 依赖规则：可依赖 COMMON、API（docs/tech/01 第 4 节）。
- 扩展方式：`RuntimeStateSource`（id 前缀 `state.`，运行状态消息来源，实现须廉价）与其他扩展点接口同模式——
  实现接口、起稳定字符串 id、在自己模块的 DI 里注册。
