# agent-api

- 类型：API（纯 Kotlin/JVM）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.agent`
- 职责（docs/tech/01 第 3 节，docs/tech/04）：`Tool`/`ScreenActionTool`/`ToolSpec` DSL、`ToolContext`、
  `ThreadEvent`/`EventPayload`（payload 带 `v`）、`ThreadStore`、`AgentSession`/`RunHandle`/`RunState`/
  `AgentEvent`、扩展点接口（`ContextContributor`、`HistoryRenderRule`、`Compactor`、`ToolResultEnricher`、
  `ThreadEventIndexer`/`ThreadRecallRetriever`）、`AgentErrorCodes`。
- 依赖规则：可依赖 COMMON、API（docs/tech/01 第 4 节）。
