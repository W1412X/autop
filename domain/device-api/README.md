# device-api

- 类型：API（纯 Kotlin/JVM）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.device`
- 职责（docs/tech/01 第 3 节，docs/tech/05）：`ScreenSnapshot`/`NodeSnapshot`/`WindowSnapshot`、
  `NodeLocator`/`ResolvedNode`/`NodeResolver`、`Observation`、`ScreenModel`/`ScreenNode`/`ScreenQuality`/
  `ObserveMode`（统一屏幕模型）、`ScreenReader`/`Screenshot`/`GestureController`（`InjectionResult`/
  `ShellSkip` 通道留痕）/`GlobalAction`、`UiStabilizer`（`StableWait`）、`AppLauncher`、`ScreenLease`、
  `UserActivityMonitor`、`NotificationFeed`、`DeviceStateProvider`、`OverlaySpec`/`OverlayHost`、
  `DeviceErrorCodes`。
- 依赖规则：可依赖 COMMON、API（docs/tech/01 第 4 节）。
- 扩展方式：观测方式（`ObserveMode` a11y/visual/llm）结果同等可信；运行状态来源
  （`RuntimeStateSource`，agent-api）由 device-impl 提供 `state.environment`/`state.input`/
  `state.observe`/`state.permissions` 四个实现。
