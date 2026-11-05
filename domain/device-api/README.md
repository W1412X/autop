# device-api

- 类型：API（纯 Kotlin/JVM）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.device`
- 职责（docs/tech/01 第 3 节，docs/tech/05）：`ScreenSnapshot`/`NodeSnapshot`/`WindowSnapshot`、
  `NodeLocator`/`ResolvedNode`/`NodeResolver`、`Observation`、`ScreenReader`/`Screenshot`/`GestureController`/
  `GlobalAction`、`UiStabilizer`、`AppLauncher`、`ScreenLease`、`UserActivityMonitor`、`NotificationFeed`、
  `DeviceStateProvider`、`OverlaySpec`/`OverlayHost`、`DeviceErrorCodes`。
- 依赖规则：可依赖 COMMON、API（docs/tech/01 第 4 节）。
