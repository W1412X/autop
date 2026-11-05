# memory-api

- 类型：API（纯 Kotlin/JVM）｜ 插件：`autop.jvm.library` ｜ 包名：`com.example.autop.memory`
- 职责（docs/tech/01 第 3 节，docs/tech/06）：记忆域端口与模型——`MemoryPath`（路径值对象）、
  `MemoryNode`/`MemoryWrite`/`WriteMode`/`WriteResult`/`MemorySource`/`MemoryVersion`/`ChangeType`、
  `MemoryStore`（增删改查/版本/回滚/确认）、`MemoryRetriever`/`MemorySearch`/`MemoryIndexRenderer`
  （检索通道、混合检索、L0 渲染）、`MemoryErrorCodes`。
- 依赖规则：可依赖 COMMON、API（docs/tech/01 第 4 节）。

## 组件（src/main/kotlin/…/memory/）

- `MemoryPath.kt`：`MemoryPath.parse(raw, maxSegmentChars=64, maxDepth=8): Outcome<MemoryPath>`——
  `/` 分段、空段/`.`/`..`/控制字符/超长段/超深拒绝；`ROOT`、`child(name)`（**构造不校验**，
  供内部已校验名称继续派生，questions.md N133）、`parent`/`name`/`segments()`/`value`。
- `MemoryModels.kt`：`MemoryNode`（path/title/summary/content/tags/links/source/version/updatedAt/
  childCount）、`MemoryWrite`（path/mode/title?/summary?/content/tags?/links?/source/runId?/reason?/
  evidence?，含 `MemoryEvidence`）、`WriteMode`（CREATE/UPDATE/APPEND）+`AppliedMode`（实际落库模式）、
  `WriteResult`、`MemorySource`（USER/AGENT）、`MemoryVersion`+`ChangeType`（版本快照，title/content
  前后对照）、`SearchHit`（path/title/snippet/score/retrievers）、`RankedPath`。
- `MemoryStore.kt`：`MemoryStore`——get/children(path, offset, limit)/write(w): Outcome<WriteResult>/
  delete(path, recursive, source, runId)/move/versions(path, limit)/revert(versionId)/
  recentChanges(offset, limit, source?)/changesOfRun(runId)/revertRun(runId): Outcome<Int>/
  observeTree(): Flow<Unit>/markConfirmed(path)。
- `MemorySearch.kt`：`MemoryRetriever`（通道扩展点，id 为 `retriever.path|fts|vector`）、
  `MemorySearch.search(query, prefix, k): List<SearchHit>`（多通道 RRF 融合）、
  `MemoryIndexRenderer.renderL0(maxTokens)`（`context.memory_index` 用）。
- `MemoryErrorCodes.kt`：`memory.*` 错误码（invalid_path/not_found/conflict/limit_exceeded 等）。

## 测试

- `MemoryPathTest`：解析合法路径、拒绝非法段/超深/超长、child/parent 派生、ROOT 值。
