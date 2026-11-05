# feature-overlay

- 类型：FEATURE（Android 库 + Compose）｜ 插件：`autop.android.feature` + `autop.hilt`
  ｜ 包名：`com.example.autop.feature.overlay`
- 职责（docs/tech/01 第 3 节，docs/tech/08）：悬浮球与迷你面板的 Compose 界面内容。
  窗口管理在 `device-impl`，二者经 `device-api` 的 `OverlayHost` 端口连接（docs/tech/05 第 10 节）。
- 依赖规则：可依赖 COMMON、API 与 core-ui（唯一可用的 IMPL）（docs/tech/01 第 4 节）。

## 模块结构（WP7）

- `ball/`：悬浮球六态（`BallUiState`/`BallLogic` 推导）、拖动/吸附/位置持久化（`BallPositionStore`）、
  `BallStateHolder` 进程级状态（questions.md N131）；窗口内容经 `composeOverlayView` 注入生命周期
  （N128/N132）。
- `panel/`：输入/运行/问题/拦截四面板（docs/tech/08 §5.2，`PanelMode` + `PanelLogic` 纯逻辑可测），
  `PanelController` 管窗口生命周期（focusable 切换、`ACTION_OUTSIDE`/返回键收起），
  `PanelRunActions` 经 `RunService` 端口执行动作（发送/steer/回答/暂停继续/停止/拦截处置/打开对话，N135–N137）。
- 窗口工厂由 app 组装层注册（`OverlayWindowIds.BALL`/`PANEL`），本模块不依赖 `device-impl`。
