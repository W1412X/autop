# feature-tasks

- 类型：FEATURE（Android 库 + Compose）｜ 插件：`autop.android.feature` + `autop.hilt`
  ｜ 包名：`com.example.autop.feature.tasks`
- 职责（docs/tech/01 第 3 节，docs/tech/08 §4.3）：任务列表、任务编辑（含触发配置）、任务详情（试运行）、Run 详情（简化时间线）。路由见 `TasksNavGraph`：`tasks/list`、`tasks/edit/{id?}`、`tasks/detail/{id}`、`runs/{runId}`。
- 依赖规则：可依赖 COMMON、API 与 core-ui（唯一可用的 IMPL）（docs/tech/01 第 4 节）。
