# task-impl

- 类型：IMPL（Android 库 + Room，task 领域）｜ 插件：`autop.android.library` + `autop.android.room` + `autop.hilt`
  ｜ 包名：`com.example.autop.task.impl`
- 职责（docs/tech/01 第 3 节，docs/tech/07）：`tasks.db`、精确闹钟调度、WorkManager 降级定时、
  锁屏提醒（`USE_FULL_SCREEN_INTENT`）、前台服务 `AgentHostService`、`TriggerSource` 实现、
  `RunService` 实现（`steer` 返回 `Outcome`，排队中直接落盘 STEER；`observeOpenRun` 按线程取未结束 Run）、
  入队即落盘用户输入、进程重启收口全部未结束 Run（`closeUnfinished`，写 `RunFinished`）、
  `TouchShieldWatcher`（07 §4.3：执行期触摸盾，自身窗口/自身动作排除见
  questions.md N139）。`exportSchema = true`，schema 提交到 `schemas/`。
- 依赖规则：可依赖 COMMON、API、同领域（task）CORE（task-core）、白名单 IMPL（docs/tech/01 第 4 节）。
- 扩展方式：`TriggerSource`/`ConditionEvaluator` 扩展点不变；会话层新口径见 07 §4.3（steer/恢复/入队落盘，WP8）。
- 测试（07 §9）：`RunSchedulerTest`（调度门条件 6 条）、`TriggerHubTest`（触发链 7 条）、`MigrationsTest`（Room v1 四表）；Robolectric+Room 挂起链用泵式等待（questions.md N155），`schemas/` 以 `androidComponents` 注册为单测资产（N156）。
