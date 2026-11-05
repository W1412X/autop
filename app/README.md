# app

- 类型：APP（Android 应用）｜ 插件：`autop.android.application` + `autop.hilt` ｜ 包名：`com.example.autop`
- 职责（docs/tech/01 第 3 节）：`AutopApplication`（@HiltAndroidApp）、`MainActivity`（单 Activity，
  `enableEdgeToEdge()`）、导航、DI 组装（聚合各 impl 模块的绑定）、Manifest 合并入口。
- 依赖规则：可依赖全部模块（docs/tech/01 第 4 节）；组件与权限声明在**拥有它的模块**的 Manifest 中。

## WP6b 接线（对话闭环）

- 依赖组装：补 `:agent:agent-runtime`（`AgentSession` 的 Hilt 绑定）、`:agent:agent-store`、
  `:domain:task-impl`、`:feature:feature-chat`（N106：app 是 DI 组装根，IMPL 模块依赖即带入绑定聚合）。
- 导航：`AppNavHost` 注册 `chatGraph(AutopRoutes.CHAT_LIST, AutopRoutes.CHAT_THREAD, chatNavigator)`；
  `AutopRoutes.CHAT_THREAD`/`chatThread()` 引用 feature-chat 的 `THREAD_ARG`/`NEW_THREAD`（N47 口径）。
  `ChatNavigator` 实现于 NavGraph Route 层：`replaceThread` 用 popUpTo(inclusive)+launchSingleTop
  把"新对话"页换成真实线程页；`backgroundApp` 经 `findActivity().moveTaskToBack(true)` 让出屏幕。
- 权限：`INTERNET` 在 app Manifest 声明（llm-http 是 JVM 库无 Manifest，docs/tech/01 第 7 节 WP3）；
  `QUERY_ALL_PACKAGES` 在 device-impl（工具属主模块）声明并 `tools:ignore` 豁免 lint（N127）。

## WP7 接线（悬浮球与触摸检测）

- 依赖组装：补 `:feature:feature-overlay`（悬浮球 Compose 内容与 `OverlayBallController`）。
- `AutopApplication.onCreate`：注册球窗口工厂 `overlayWindows.register(BALL, ballController.ballFactory())`，
  `ui.overlay.enabled` 开启时 `ballController.start()`（注册在组装层做：FEATURE 不依赖 IMPL，docs/tech/01 §4）。
- 触摸检测：注入 `UserActivityMonitor` 即启动（探针窗口 + a11y 事件两路，docs/tech/05 §8）。
- 悬浮窗权限：`SYSTEM_ALERT_WINDOW` 在 device-impl Manifest 声明（备用通道，ADR-0004）。
