# core-ui

- 类型：IMPL（Android 库 + Compose）｜ 插件：`autop.android.library` + Compose ｜ 包名：`com.example.autop.core.ui`
- 职责（docs/tech/01 第 3 节，docs/tech/08）：LimeTV 风格设计系统——颜色/间距/字号 token、
  基础组件规格。界面颜色与尺寸只能引用这里的 token（docs/standards/coding-standards.md 第 4 节）。
- 依赖规则：可依赖 COMMON、API、同领域 CORE、白名单 IMPL；feature 模块只允许依赖这一个 IMPL（docs/tech/01 第 4 节）。

## 主题 token（src/main/kotlin/…/ui/theme/）

- `Color.kt`：LimeTV 基线色板 → `AutopLightColors`/`AutopDarkColors`（Material3 全槽位）；
  `AutopAlpha` 透明度常量；`StatusTone` 状态语义枚举 + `AutopSemanticColors`/`LocalSemanticColors`
  （只映射 scheme 槽位，不新增色值）。
- `SeedColors.kt`：5 种子色（Lime/Blue/Orange/Violet/Graphite），`SeedColors.light/dark(seedIndex)`。
- `AutopTypography.kt`：15 个 Material3 字体样式 + `mono` 等宽样式；`Typography.scaled(factor)` 字号缩放。
- `Scale.kt`：`Spacing` 三档密度（COMPACT/STANDARD/COMFORTABLE）+ `LocalSpacing`；
  `IconSize` 枚举 + `LocalIconScale` + `IconSize.scaled()`。
- `Shape.kt`：`AutopShapes`（extraSmall 4 … extraLarge 28）。
- `AutopDimens.kt`：docs/tech/08 §1.4 与 §2 的固定尺寸（按钮 44、底栏 64、缩略图 48 等）；UI 层不写 dp 字面量。
- `Appearance.kt`：`DarkMode`/`UiDensity`/`AppearanceSettings`（主题参数快照）+
  `UiAppearanceSection.toAppearanceSettings()`（配置段 → 快照）。
- `Theme.kt`：`AutopTheme(appearance, content)` 组装颜色/字体/形状/间距/图标缩放/语义色；
  `LocalMotionEnabled` 动效开关（关闭时 shimmer 静态）。

## 组件（src/main/kotlin/…/ui/component/，docs/tech/08 §2）

- 按钮：`AutopButton`/`AutopTonalButton`/`AutopOutlinedButton`/`AutopTextButton`、`AutopIconButton`、`IconAction`。
- Chip：`AutopChip`（高 34）；`StatusChip`（高 24、`StatusTone` 语义色、图标+文字双通道）。
- 分组：`SectionHeader`（4×18 竖条）；`SectionTitle`。
- 设置行：`SwitchRow`/`OptionRow`/`InfoRow`/`ActionRow`/`NavRow`（最小触控区 48）。
- 列表：`ListRow`（48dp 图标块、可选 3dp 进度条）+ `subtitleParts`；`PagedList`（距末尾 6 项自动加载，
  尾部加载/错误/到底三态，状态 `PagedListState`）。
- 输入：`AutopInputBar`（BasicTextField 自绘，规格 `InputBarSpec`）。
- 顶栏：`SubPageTopBar`（手写，不用 Scaffold/TopAppBar）；`TopLevelHeader`。
- 状态与提示：`EmptyState`/`ErrorState`/`LoadingState`；`ToastEffect`（一次性提示，系统 Toast，替代原 InlineBanner 与 Snackbar）；
  `SkeletonRow`/`Modifier.shimmer`（`ui.motion.shimmerMs` 覆盖默认 900）。
- 弹层：`ConfirmDialog`；`OptionSheet`（选项 `OptionItem`）。
- 图标：`AutopIcons` 统一入口；tint 回落 `LocalContentColor`（沿用 LimeTV 修过的深色黑图标方案，
  自定义色用 `CompositionLocalProvider(LocalContentColor provides …)`）。

## 预览（preview/ + 各 `*Previews.kt`）

- `PreviewData` 统一样本、`PreviewTheme` 亮/暗包装；每个公共组件亮/暗各一例 `@Preview`。

## 与 LimeTV 的差异

- LimeTV 为 TV 端；本模块按 docs/tech/08 用 Jetpack Compose + Material3 重写同风格设计系统，
  色板取 LimeTV 基线值；禁 TopAppBar/Card/Snackbar/阴影（docs/standards/coding-standards.md）。
- 组件 API 只接收数据与回调，不持有业务状态。

## 内边距映射（questions.md N18）

- §2 的 16×6 / 16×10 等间距映射到 `LocalSpacing` token；`TopLevelHeader` 的 24/12/32/20
  按规格精确取 `AutopDimens.headerPadding*`；`SectionTitle` 用 start/top spacing.lg、bottom spacing.xs。
