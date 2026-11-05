# task-api

- 类型：API（纯 Kotlin/JVM）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.task`
- 职责（docs/tech/01 第 3 节，docs/tech/07）：`Task`、`Trigger`（Manual/Schedule/Condition）、`ScheduleSpec`
  （Once/Cron）、`FilterGroup`/`FilterClause`/`FilterOp`、`RunRecord`/`RunStatus`、`RunService`、
  `TaskRepository`、`TaskDraftGenerator`、`TriggerSimulator`、`UserNotifier`、`TriggerSource`/
  `ConditionEvaluator`/`ConditionResult`、`TaskErrorCodes`。
- 依赖规则：可依赖 COMMON、API（docs/tech/01 第 4 节）。
