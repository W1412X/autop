# core-common

- 类型：COMMON（纯 Kotlin/JVM）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.core.common`
- 职责（docs/tech/01 第 3 节）：`Outcome`、`AppError`、`RunId/ThreadId/TaskId`、`IdGenerator`、`Clock`、
  `DispatcherProvider`、`Logger`/`LogRedact`、`AutopJson`、`Capability`、`SecretStore` 端口、`DeviceQuirks`、
  `BigramTokenizer`、`TextTokenEstimator`、`RankFusion`、`runCatchingCancellable`。
- 依赖规则：不允许依赖任何项目内模块（docs/tech/01 第 4 节）。
