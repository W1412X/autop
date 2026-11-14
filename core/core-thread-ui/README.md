# core-thread-ui

- 类型：IMPL（Android 库 + Compose）｜ 插件：`autop.android.library` + compose
  ｜ 包名：`com.example.autop.core.thread.ui`
- 职责（ADR-0031）：对话时间线的共享渲染。对话页（feature-chat）与任务运行详情（feature-tasks）
  都用它显示同一套消息、步骤、横幅、分叉标记和元信息行。
- 依赖规则：只依赖 COMMON（core-common）、API（agent-api）与 core-ui；不依赖任何 feature 模块。

## 对外接口

- `TimelineMerger`（`mergeTimeline`）：把 ThreadEvent 与 AgentEvent 归并为 `TimelineEntry`。
- `TimelineItem` / `TimelineEntry`：时间线的展示模型。
- `TimelineRow(entry, onAction: (TimelineAction) -> Unit)`：按条目类型分派渲染。
- `TimelineAction`：条目发出的用户操作；页面负责映射到自己的动作类型。
- `StepLabel` / `StepDescriber` / `StepIcons`：工具步骤的动词、参数描述与图标。
- `Thumbnail`、`decodeSampledFile`、`decodeSampledBytes`：图片缩略图解码。

## 不属于本模块

- 对话页专用的 `AskCard`（等待回答）、输入区、顶栏与弹层：留在 feature-chat。
