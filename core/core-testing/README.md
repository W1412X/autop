# core-testing

- 类型：CORE（纯 Kotlin/JVM）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.core.testing`
- 职责（docs/tech/01 第 3 节、02 §5）：`FakeClock`、`TestDispatcherProvider`、`FakeConfigProvider`、
  `FakePromptRepository`、`Fixtures` 夹具加载器；Provider 契约测试支撑（docs/tech/03 §5.3）：
  `ContractFixtures`/`ContractTool`/`FakeSecretStore`/`CollectResult`、`ContractTestSupport` 服务器工具、
  `ContractTarget` 装配端口 + `ProviderContractTest` 9 用例基类。
- `FakeConfigProvider` 用真实的合并逻辑（`buildConfigSnapshot`）与真实的 assets 默认值文件
  （Gradle 系统属性 `autop.configDir` 指向 `core-config-impl/src/main/assets/config`）；
  `FakePromptRepository` 同理读真实模板（`autop.promptDir`）。
- `Fixtures.load(path)` 读取消费模块 `src/test/resources/fixtures/` 下的文件（经测试 classpath）。
- 契约测试：适配器子类实现 `ContractTarget`（夹具、`createProvider`、`assertImageRequest`）并继承
  `ProviderContractTest`，即可跑 docs/tech/03 §5.3 的 9 用例；线格式夹具放各适配器
  `src/test/resources/fixtures/01-…09-…`。本模块因此 `api` 依赖 `llm-api` 与 `mockwebserver3`。
- 依赖规则：**只能**出现在 `testImplementation`，出现在主配置即构建失败（docs/tech/01 第 4 节）。
