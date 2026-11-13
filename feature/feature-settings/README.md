# feature-settings

- 类型：FEATURE（Android 库 + Compose）｜ 插件：`autop.android.feature` + `autop.hilt`
  ｜ 包名：`com.example.autop.feature.settings`
- 职责（docs/tech/01 第 3 节，docs/tech/08）：我的 / 设置（模型配置、API Key、安全与策略、
  高级参数、调试页与快照导出、健康检查入口）。
- 依赖规则：可依赖 COMMON、API 与 core-ui（唯一可用的 IMPL）（docs/tech/01 第 4 节）。
  当前依赖：core-common（Outcome/Logger/DispatcherProvider）、core-config（ConfigProvider/
  ConfigEditor 端口）、core-ui（组件）；实现由 app 组装注入。

## WP2 现状

- `me` 顶级页骨架（docs/tech/08 §4.5）：LazyColumn + SectionTitle 分组 + 组间 HorizontalDivider。
- 外观分组完整实现：深色模式 / 动态取色 / 主题色 / 文字大小 / 图标大小 / 布局密度 / 动效，
  经 `AppearanceSettingsViewModel` 写 `ui.appearance.<字段>` 用户覆盖，主题即时生效。
- 其余分组（模型、权限与运行、安全、任务与提醒、高级）为占位 NavRow，点击显示
  "即将推出" InlineBanner（时长 `ui.banner.durationMs`）。
- 导航：`NavGraphBuilder.meGraph(route, navigator: SettingsNavigator)`（docs/tech/08 §3），
  子页（me/models 等）在后续 WP 扩展。
- 文件：`SettingsNavigator` / `MeNavGraph`；`me/` 下 `MeScreen`（无状态）+ `MeUiState` +
  `MeAction` + `AppearanceSettingsViewModel` + `AppearanceOptions`/`SheetKind`（档位与弹层文案）。
- 测试：`AppearanceSettingsViewModelTest`（外观 7 项写入路径与即时生效链路）。

## WP3 新增（模型配置 / 检索向量）

- 子页路由：`me/{id}`（docs/tech/08 §4.5），id 见 `SettingsSection.id`；参数名
  `ME_SECTION_ARG` 由本模块定义、app 的 `AutopRoutes.ME_SECTION` 引用组装（防模板漂移）；
  未知 id 兼容页兜底。
- 模型配置页（`me/models`，docs/tech/08 §4.5）：`ModelsScreen`（列表 PagedList +
  角色 StatusChip）→ `ModelEditScreen`（草稿态内嵌列表目的地，不另开路由）：协议、名称、
  接口地址、API Key（密码输入、已存密钥只显末 4 位）、模型名、上下文窗口、最大输出、
  quirks 折叠区；测试连接三行结果 + 能力回填提示；角色分配 OptionSheet；删除确认。
  `ModelProfilesEditor` 门面聚合 ModelProfileStore + SecretStore + IdGenerator。
- 检索向量页（`me/embedding`）：提供方（none/openai，记忆与对话共用）、模型配置、
  向量维度；模型名输入 400ms 防抖；重建记忆/对话索引进度槽（WP6/WP10 接线）。
- 写入语义：EmbeddingViewModel 整段写 `llm.embedding`（字段联动重置 dimension）。
- 测试：`ModelsViewModelTest`（11 用例）、`EmbeddingViewModelTest`（7 用例）、
  `AppearanceSettingsViewModelTest`（含模型入口副标题与 hasPage 判断）。

## WP7 新增（权限与运行组）

- `MePermissionsSection`（docs/tech/08 §4.5）：权限检查 NavRow + `OptionRow` 悬浮窗通道
  （auto / accessibility / application，写 `ui.overlay.windowType`；通道切换由 device-impl 的
  `OverlayChannelResolver` 变化流重建窗口，立即生效）。原 `SwitchRow` 触摸屏幕时暂停任务
  已随 N186 触摸盾落地删除（执行期固定屏蔽用户触摸，docs/tech/05 §8.1）。
- `MeScreen` 结构调整：外观分组拆至 `MeAppearanceSection.kt`、弹层宿主拆至
  `AppearanceSheetHost`/`WindowTypeSheet`，满足 detekt LongMethod/TooManyFunctions 阈值。

## “我的”两级结构（docs/tech/08 §4.5）

- 一级 `MeScreen`：健康状态卡（2×3 网格，前四项为必需项决定卡片颜色，注入通道为可选项）与七个分类行。分类行副标题显示当前值。状态格和分类行都可点击跳转。
- 二级 `MeGroupScreen`，路由 `me/group/{group}?focus={focus}`。`meGraph` 新增 `groupRoute` 参数。分类内容复用既有分组组件。选择弹层的开关由 `MeSheetState` 持有，`MeSheetHost` 渲染。
- `SettingsNavigator` 新增 `openGroup(group, focus)` 与 `openSystemSettings(target)`，由 app 实现。
