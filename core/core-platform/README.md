# core-platform

- 类型：IMPL（Android 库）｜ 插件：`autop.android.library` + `autop.hilt` ｜ 包名：`com.example.autop.core.platform`
- 职责（docs/tech/01 第 3 节）：`PlatformCapabilities`、`SecretStore` 实现（Android Keystore）、
  `Logger` 实现（脱敏后写 logcat/文件）、`DeviceQuirks` 规则加载（`rules/device_quirks.json`）。
- 组件补充（docs/tech/03 §6）：`BitmapImageLoader.kt`——llm-api `ImageLoader` 的 Android 实现，
  采样解码 + 等比缩放到 `maxEdge`，png→PNG、其余→JPEG 编码。
- 依赖规则：可依赖 COMMON、API、同领域 CORE、白名单 IMPL（docs/tech/01 第 4 节）。
