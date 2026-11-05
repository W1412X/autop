# agent-runtime

- 类型：CORE（纯 Kotlin/JVM，agent 领域）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.agent.runtime`
- 职责（docs/tech/01 第 3 节，docs/tech/04）：`DefaultAgentSession`、`RunController`、`TurnLoop`、
  `ContextAssembler`、`HistoryRenderer` 与内置渲染规则、`CompactionCoordinator`（Handoff/Deterministic）、
  `ToolRouter`/`ArgsValidator`、内置控制类工具（update_plan/finish/ask_user/history_recall）、
  `Budget`/`CircuitBreaker`、`JsonToolProtocol` 兜底、DI 绑定（`di/`）。
- 依赖规则：可依赖 COMMON、API、同领域（agent）CORE（docs/tech/01 第 4 节）。
