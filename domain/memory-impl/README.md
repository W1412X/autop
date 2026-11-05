# memory-impl

- 类型：IMPL（Android 库 + Room，memory 领域）｜ 插件：`autop.android.library` + `autop.android.room` + `autop.hilt`
  ｜ 包名：`com.example.autop.memory.impl`
- 职责（docs/tech/01 第 3 节，docs/tech/06）：`memory.db`（nodes、versions、fts、embeddings）、
  FTS4 bigram 分词检索、向量检索、混合检索（RRF）、记忆工具、记忆相关 `ContextContributor`。
  `exportSchema = true`，schema 提交到 `schemas/`。
- 依赖规则：可依赖 COMMON、API、同领域（memory）CORE、白名单 IMPL（docs/tech/01 第 4 节）。

## 组件（src/main/kotlin/…/memory/impl/）

- `db/MemoryDb.kt` / `db/MemoryEntities.kt` / `db/MemoryDaos.kt`：Room 库（版本 1 起配迁移）；
  实体为节点、版本、FTS4 分词列、向量块；FTS 匹配用 `@RawQuery`（AND→OR 退化由调用方换查询串）。
- `MemoryStoreImpl.kt`：`MemoryStore` 实现（06 §2.4）——写入事务语义、版本记录、用户内容保护；
  写入模式分派在 `MemoryWriter`，版本撤销在 `MemoryReverter`，实体互转在 `MemoryMappers.kt`。
- `MemoryWriter.kt`：CREATE/UPDATE/APPEND 三模式与用户内容保护的 SUPPLEMENT 降级；单事务内完成
  版本 +1、写 `memory_versions`、同步 `memory_fts`、path 入向量索引队列。
- `MemoryReverter.kt`：按 `MemoryVersion` 的 before 值恢复节点并记 REVERT 版本；按 Run 撤销 =
  该 Run 的变更按时间倒序逐条撤销。
- `MemoryFtsSync.kt`：节点写入/删除/移动时同步 `memory_fts` 分词列。
- `MemoryText.kt`：纯函数——摘要截取、embedding 分块、内容指纹（`textHash` 跳过未变块）。
- `FtsRanker.kt`：FTS4 无内置 bm25，用 `matchinfo(memory_fts, 'pcnalx')` 自算 BM25 近似
  （k1=1.2、b=0.75，标题列权重高于正文列，questions.md N141 修正了 pcnalx 布局读取）。
- `retriever/PathRetriever.kt`（`retriever.path`：query 切词与标题/路径各段比较，全等 3/前缀 2/包含 1）、
  `retriever/FtsRetriever.kt`（`retriever.fts`：BigramTokenizer 切词 AND 连接，零结果退化 OR 重查；
  bigram 使 snippet() 输出为词元拼接，故 snippet 由融合层用 summary 兜底）、
  `retriever/VectorRetriever.kt`（`retriever.vector`：query 向量化后与内存缓存暴力余弦，
  取 top `k × vectorOverfetch`，同 path 多块取最高分；未配置 embedding 时返回空）。
- `HybridMemorySearch.kt`：按 `memory.search.channels` 并发执行各检索通道，`RankFusion.rrf` 融合；
  snippet 优先 FTS 片段、其次 summary；结果 links 中的节点各附一行；命中后 `accessCount + 1`。
- `VectorCache.kt`：向量内存缓存——启动全量加载当前 modelId 的向量，写入增量更新，检索暴力余弦。
- `EmbeddingIndexer.kt`：单协程消费队列，按段落分块向量化；`textHash` 未变的块跳过；失败退避重试，
  超次数记日志放弃（下次写入重来）；配置的 embedding 模型变化（modelId 不同）→ 全量重建。
- `MemoryIndexRendererImpl.kt`：L0 索引渲染（06 §2.7）——一级为根目录行（path + summary + 子条数），
  预算允许时追加二级标题列表；超 `maxTokens` 去掉二级列表。模板 `memory.context.index`。
- `MemorySeed.kt`：内置根目录 `/profile`、`/people`、`/apps`、`/tasks`；标题与摘要来自 strings.xml，
  根目录与四个内置目录不能删除、不能移动（`memory.root_protected`）。
- `contributor/MemoryContributors.kt`：`context.memory_index`（L0 索引）、`context.user_profile`
  （用户画像）、`context.trigger_memory`（事件触发时预取记忆）。
- `contributor/AppManualEnricher.kt`：`enricher.app_manual`——工具输出的 `observation.packageName`
  在本 Run 首次出现且 `/apps/{package}/_manual` 存在时注入手册摘要（模板 `memory.context.app_manual`）。
- `tools/`：`MemoryReadTools`（`tool.memory_list`/`memory_read`/`memory_search`，返回正文不含不可信
  封装）、`MemoryWriteTool`（`tool.memory_write`，evidence 说明从哪里得知；写 `/apps/{package}/…`
  时自动建 `/apps/{package}` 目录）、`MemoryToolSpecs`（参数结构，说明取模板 `meta["param.<名>"]`）、
  `ToolSupport`（输出与参数辅助）。
- `di/MemoryModule.kt`：`memory.db`、`MemoryStore` 实现、混合检索三通道、L0 渲染器、
  记忆工具与上下文贡献者的多绑定。

## 测试

- `MemoryStoreImplTest`：写入事务/版本/回滚/Run 撤销/用户内容保护/根目录保护。
- `HybridMemorySearchTest`：RRF 融合、snippet 兜底、命中计数。
- `FtsRankerTest`：matchinfo 'pcnalx' 布局下的 BM25 近似排序。
- `EmbeddingIndexerTest`：分块、textHash 跳过、modelId 变化重建。
- `VectorCacheTest`：增量更新、余弦 top N、同 path 取最高分。
- `MemoryTextTest`：摘要截取、分块、指纹。
- `testing/MemoryTestDoubles.kt`：内存实现的测试替身。
