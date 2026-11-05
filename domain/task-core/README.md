# task-core

- 类型：CORE（纯 Kotlin/JVM，task 领域）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.task.core`
- 职责（docs/tech/01 第 3 节，docs/tech/07）：cron 解析与下次触发时间、`FilterGroup` 求值、
  冷却与限额判定、触发队列（差异 D3）、`TriggerSimulator` 核心算法。
- 依赖规则：可依赖 COMMON、API、同领域（task）CORE（docs/tech/01 第 4 节）。
