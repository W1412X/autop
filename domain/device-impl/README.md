# device-impl

- 类型：IMPL（Android 库，device 领域）｜ 插件：`autop.android.library` + `autop.hilt`
  ｜ 包名：`com.example.autop.device.impl`
- 职责（docs/tech/01 第 3 节，docs/tech/05）：`AutopAccessibilityService`、通知监听、`dispatchGesture`
  手势、`takeScreenshot` 截图、等待稳定、触摸检测（双路 + 自家窗口矩形排除，questions.md N139）、
  悬浮窗宿主（双通道窗口管理，ADR-0004）、设备类工具实现（观测自动选择；动作工具不自动观测，
  收尾 `ActionEpilogue` 按 `device.action.delayMs` 固定等待并生成回放记录与执行记录 trace，
  见 docs/design/tool_optimize 与 ADR-0025）、运行状态来源实现（`state.environment`/
  `state.input`/`state.observe`/`state.permissions`，注册为 `RuntimeStateSource`）、
  序列化规则 `rules/serializer_rules.json`、重启规则 `rules/restart_rules.json`（`restart/RestartRulesLoader`）。
- 依赖规则：可依赖 COMMON、API、同领域（device）CORE（device-core）、白名单 IMPL（docs/tech/01 第 4 节）。
- 扩展方式：运行状态来源在 `di/` 里 `@Provides @IntoSet` 注册为 `RuntimeStateSource`（与注册
  `ContextContributor`/`Tool` 的写法一致）。
- 视觉解析（`vision/`，ADR-0026）：YOLO 控件框 → 叶子内 det 定位文字行 → 逐行 rec，图标分类并行；
  所有推理经 `InferenceGate` 共用 `device.vision.parallel.maxThreads` 个并发名额。新增一个推理引擎
  时实现 `vision/VisionEngines.kt` 里对应端口，并在 `VisualScreenParserImpl` 装配；模型资产放
  `assets/vision/` 并写入 `manifest.json`（`VisionAssetsManifestTest` 会核对清单与哈希）。
