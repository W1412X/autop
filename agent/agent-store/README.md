# agent-store

- 类型：IMPL（Android 库 + Room，agent 领域）｜ 插件：`autop.android.library` + `autop.android.room` + `autop.hilt`
  ｜ 包名：`com.example.autop.agent.store`
- 职责（docs/tech/01 第 3 节，docs/tech/04）：`ThreadStore` 的 Room 实现——`threads.db`（events、baselines、
  fts 索引），`exportSchema = true`，schema 提交到 `schemas/`。
- 依赖规则：可依赖 COMMON、API、同领域（agent）CORE（agent-runtime）、白名单 IMPL（docs/tech/01 第 4 节）。
