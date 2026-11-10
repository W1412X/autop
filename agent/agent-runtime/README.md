# agent-runtime

- 类型：CORE（纯 Kotlin/JVM，agent 领域）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.agent.runtime`
- 职责（docs/tech/01 第 3 节，docs/tech/04）：`DefaultAgentSession`、`RunController`、`TurnLoop`、
  `TurnSampler`（流式回合级重试）、`ContextAssembler`、`HistoryRenderer` 与内置渲染规则、
  `CompactionCoordinator`（Handoff/Deterministic，压缩后重装 system）、`ToolRouter`/`ArgsValidator`
  （批次跳过、屏幕读串行）、`TurnGuard`（截断/空回复/拒答分流）、内置控制类工具
  （update_plan/finish/ask_user/history_recall/note/get_device_state）、`Budget`/`CircuitBreaker`、
  `state/`（`RuntimeStateTracker`、`BudgetStateSource`、`ToolVisibilityStateSource`）、
  `JsonToolProtocol` 兜底、DI 绑定（`di/`）。
- 依赖规则：可依赖 COMMON、API、同领域（agent）CORE（docs/tech/01 第 4 节）。
- 扩展方式：运行状态来源经 `RuntimeStateSource` 多绑定注入（`Set<RuntimeStateSource>`，照
  `Set<ContextContributor>` 的方式）；runtime 提供空集合 `@Multibinds` 保证无实现时可注入。
