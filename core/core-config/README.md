# core-config

- 类型：API（纯 Kotlin/JVM）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.core.config`
- 职责（docs/tech/01 第 3 节）：`ConfigKey`、`ConfigSection`、`ConfigProvider`、`ConfigSnapshot`、
  合并算法（用户覆盖 ⊕ Task 覆盖 ⊕ Run 覆盖）、校验与迁移接口。
- 依赖规则：可依赖 COMMON、API（docs/tech/01 第 4 节）。
