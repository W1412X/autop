# device-impl

- 类型：IMPL（Android 库，device 领域）｜ 插件：`autop.android.library` + `autop.hilt`
  ｜ 包名：`com.example.autop.device.impl`
- 职责（docs/tech/01 第 3 节，docs/tech/05）：`AutopAccessibilityService`、通知监听、`dispatchGesture`
  手势、`takeScreenshot` 截图、等待稳定、触摸检测（双路 + 自家窗口矩形排除，questions.md N139）、
  悬浮窗宿主（双通道窗口管理，ADR-0004）、设备类工具实现、
  序列化规则 `rules/serializer_rules.json`。
- 依赖规则：可依赖 COMMON、API、同领域（device）CORE（device-core）、白名单 IMPL（docs/tech/01 第 4 节）。
