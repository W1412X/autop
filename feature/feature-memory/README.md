# feature-memory

- 类型：FEATURE（Android 库 + Compose）｜ 插件：`autop.android.feature` + `autop.hilt`
  ｜ 包名：`com.example.autop.feature.memory`
- 职责（docs/tech/01 第 3 节，docs/tech/08）：记忆树浏览、编辑（含 pinned）、最近变更时间线。
- 依赖规则：可依赖 COMMON、API 与 core-ui（唯一可用的 IMPL）（docs/tech/01 第 4 节）。

## 组件（src/main/kotlin/…/feature/memory/）

- `MemoryNavGraph.kt`：记忆子图入口——`memoryGraph(browseRoute, nodeRoute, changesRoute, navigator)`
  注册浏览、条目、最近变更三个 composable；路由参数 `path` 经 `SavedStateHandle` 由 ViewModel 读取
  （Screen 不透传，避免 UnusedParameter）。
- `MemoryNavigator.kt`：导航端口（openBrowse/openNode/openChanges/navigateBack），app 侧实现。
- `browse/`：浏览页（`/` 根目录起）——目录/条目列表分页（`PagedList`）、搜索框、新建条目/目录
  （`CreateNameDialog`，`MemoryPath.parse` 校验段名）、最近变更入口（副标题带变更数）。
  `BrowseViewModel` 经 SavedStateHandle 读 `path`，子目录浏览是 VM 内部状态切换而非路由跳转
  （questions.md N142）。
- `node/`：条目页——标题/摘要/正文编辑（`NodeForm`）、tags（FlowRow + 追加对话框）、links、
  版本列表（`NodeSheets`：版本对话框 + 差异对话框）、移动/删除（内置目录受 `memory.root_protected`
  保护）、确认标记。`VersionDiff.kt`/`DiffLine.kt`：LCS 逐行差异（+/-/上下文）。
- `changes/`：最近变更时间线——按 Run 分组（标题 `Run %1$s` 用 runId 短码，questions.md N143），
  支持"撤销此变更"（revert）与"撤销该 Run"（revertRun）。
- `src/main/res/values/strings.xml`：全部 UI 文案。

## 设计说明

- 目录/条目以 `content.isBlank()` 判定（06 §2.2 自动建目录即空节点）；新建条目未填正文前按目录
  图标展示——数据模型无类型字段的限制（questions.md N144）。
- "恢复到此版本"实现为"撤销此变更"（revert，以该版本 before 值回写）（questions.md N145）。
- 正文"最小 8 行"未实现（`InputBarSpec` 仅 maxLines）（questions.md N146）。
