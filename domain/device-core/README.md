# device-core

- 类型：CORE（纯 Kotlin/JVM，device 领域）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.device.core`
- 职责（docs/tech/01 第 3 节，docs/tech/05）：屏幕快照 15 步序列化算法、Run 内稳定的短 ID 注册表、
  差量观测、快照指纹、`NodeLocator` 匹配与评分。
- 依赖规则：可依赖 COMMON、API、同领域（device）CORE（docs/tech/01 第 4 节）。
