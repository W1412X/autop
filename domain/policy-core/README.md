# policy-core

- 类型：CORE（纯 Kotlin/JVM，policy 领域）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.policy.core`
- 职责（docs/tech/01 第 3 节，docs/tech/06）：规则引擎求值（TARGET/SCREEN 两档）、规则类型实现
  （password_field/text_any/package/activity_regex/secure_with_text/uri_scheme）、categories 与
  overridable 判定、脱敏输出。
- 依赖规则：可依赖 COMMON、API、同领域（policy）CORE（docs/tech/01 第 4 节）。
