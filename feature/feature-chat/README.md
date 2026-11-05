# feature-chat

- 类型：FEATURE（Android 库 + Compose）｜ 插件：`autop.android.feature` + `autop.hilt`
  ｜ 包名：`com.example.autop.feature.chat`
- 职责（docs/tech/01 第 3 节，docs/tech/08）：对话列表、对话页（消息流、输入框、工具卡、运行控制）。
- 依赖规则：可依赖 COMMON、API 与 core-ui（唯一可用的 IMPL）（docs/tech/01 第 4 节）。
  当前依赖：core-common、core-config、llm-api、agent-api、device-api、task-api、core-ui；
  实现（RunService/ThreadStore/ConfigProvider）由 app 组装注入。

## WP6b 现状（对话闭环）

- 导航：`NavGraphBuilder.chatGraph(route, threadRoute, navigator: ChatNavigator)`（docs/tech/08 §3）；
  路由参数名 `THREAD_ARG`（新对话哨兵 `NEW_THREAD`）由本模块定义、app 组装引用。
  导航类动作（新建/打开/无障碍设置/让出屏幕/返回）由 NavGraph 的 Route 层拦截转
  `ChatNavigator`，ViewModel 只产出 `onAction(action)` 与一次性 `navEvents`（替换线程/返回/后台）。
- 对话列表页（`list/`，docs/tech/08 §4.1）：运行中卡片（进度 = stepCount/预算）+ 分页列表行
  （标题/预览/RunOutcome/相对时间 `ageOf`）+ 长按菜单（重命名/删除）+ 无障碍未连接提示；
  `ChatListViewModel` 从 ThreadStore 分页观察、RunService 观察活跃 Run。
- 对话页（`thread/`，docs/tech/08 §4.2）：顶栏（标题/更多菜单/无障碍提示/一次性 InlineBanner）+
  LazyColumn 时间线 + AskCard（等待回答选项）+ InputArea（附屏幕 chip/输入框/发送/停止）。
- 时间线归并：`TimelineMerger` 把 ThreadEvent 持久化事件 + AgentEvent 实时增量归并为
  `TimelineEntry`（流式文本合并一条、ToolResult 就地更新 StepRow、连续读步骤按
  `ui.chat.stepGroupThreshold` 折叠、批次尾无未配对 ToolCall 处标记 fork 点、
  RunFinished 追加结果卡）。
- 步骤描述：`StepLabel` 枚举（toolId → 动词资源）+ `StepDescriber` 表驱动参数拼接
  （quoted/plain/paren 三模板，未知工具回落 toolId）。
- 配置：`ui.chat.inputMaxLines/backgroundOnSend` 经 `ChatThreadUiState` 携带到 Screen；
  `backgroundOnSend` 为真时发送后发 `ChatNavEvent.Background`，由 app 侧 moveTaskToBack。
- 测试：`TimelineMergerTest`（docs/tech/08 §7 四项 + 结果卡统计）；页面 Preview：
  `ChatListScreenPreviews` / `ChatThreadScreenPreviews`（亮暗各一组，覆盖运行中与等待回答样式）。
