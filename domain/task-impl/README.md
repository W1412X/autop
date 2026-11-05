# task-impl

- 类型：IMPL（Android 库 + Room，task 领域）｜ 插件：`autop.android.library` + `autop.android.room` + `autop.hilt`
  ｜ 包名：`com.example.autop.task.impl`
- 职责（docs/tech/01 第 3 节，docs/tech/07）：`tasks.db`、精确闹钟调度、WorkManager 降级定时、
  锁屏提醒（`USE_FULL_SCREEN_INTENT`）、前台服务 `AgentHostService`、`TriggerSource` 实现、
  `RunService` 实现、`TouchPauseWatcher`（07 §4.3：用户触摸时暂停，自身窗口/自身动作排除见
  questions.md N139）。`exportSchema = true`，schema 提交到 `schemas/`。
- 依赖规则：可依赖 COMMON、API、同领域（task）CORE（task-core）、白名单 IMPL（docs/tech/01 第 4 节）。
- 测试（07 §9）：`RunSchedulerTest`（调度门条件 6 条）、`TriggerHubTest`（触发链 7 条）、`MigrationsTest`（Room v1 四表）；Robolectric+Room 挂起链用泵式等待（questions.md N155），`schemas/` 以 `androidComponents` 注册为单测资产（N156）。
