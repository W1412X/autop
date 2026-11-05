# core-config-impl

- 类型：IMPL（Android 库）｜ 插件：`autop.android.library` + `autop.hilt` ｜ 包名：`com.example.autop.core.config.impl`
- 职责（docs/tech/01 第 3 节）：从 assets 加载配置默认值（`config/*.json`，带 `schemaVersion`）、
  DataStore 用户覆盖层、`ConfigProvider` 实现、迁移与校验执行。
- 组件补充（docs/tech/03 §3）：`ModelProfilesStore.kt`——`model_profiles.json` 独立 DataStore
  （模型配置 + 角色分配，带 `schemaVersion`，损坏或超前版本回退空文档，questions.md N38）；
  `di/ModelProfilesModule.kt` 装配 `ModelProfileStore` 端口（llm-api）。
- 依赖规则：可依赖 COMMON、API、同领域 CORE、白名单 IMPL（core-platform 等）（docs/tech/01 第 4 节）。
