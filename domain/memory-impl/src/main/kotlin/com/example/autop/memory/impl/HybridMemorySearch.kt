/**
 * 混合检索（docs/tech/06 §2.6）：按 `memory.search.channels` 并发执行各检索通道，
 * 用 `RankFusion.rrf` 融合；snippet 优先 FTS 片段，其次 summary；
 * 结果 links 中的节点各附一行；命中后 `accessCount + 1`。
 */
package com.example.autop.memory.impl

import com.example.autop.core.common.Clock
import com.example.autop.core.common.RankFusion
import com.example.autop.core.common.Ranked
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.MemoryConfig
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemoryRetriever
import com.example.autop.memory.MemorySearch
import com.example.autop.memory.MemorySource
import com.example.autop.memory.SearchHit
import com.example.autop.memory.impl.db.MemoryDb
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/** RRF 混合检索。 */
@Singleton
public class HybridMemorySearch
    @Inject
    constructor(
        private val db: MemoryDb,
        private val retrievers: Set<@JvmSuppressWildcards MemoryRetriever>,
        private val configProvider: ConfigProvider,
        private val clock: Clock,
    ) : MemorySearch {
        override suspend fun search(
            query: String,
            prefix: MemoryPath?,
            k: Int,
        ): List<SearchHit> {
            val cfg = configProvider.current()[MemoryConfig.SEARCH]
            val byId = retrievers.associateBy { it.id }
            val channelLists =
                coroutineScope {
                    cfg.channels
                        .map { channelId ->
                            async {
                                channelId to
                                    (byId[channelId]?.search(query, prefix, cfg.perChannelLimit) ?: emptyList())
                            }
                        }.awaitAll()
                }
            val ranked =
                channelLists.associate { (channelId, list) ->
                    channelId to list.mapIndexed { i, r -> Ranked(key = r.path, rank = i + 1) }
                }
            val fused = RankFusion.rrf(ranked, cfg.weights, cfg.rrfK)
            return fused.take(k).mapNotNull { buildHit(it) }
        }

        private suspend fun buildHit(fused: com.example.autop.core.common.FusedRank<MemoryPath>): SearchHit? {
            val node = db.nodeDao().get(fused.key.value) ?: return null
            db.nodeDao().touch(node.path, clock.nowMillis())
            val linkLines =
                decodeList(node.linksJson)
                    .mapNotNull { db.nodeDao().get(it) }
                    .joinToString(separator = "\n") { "  - ${it.title}：${it.summary}" }
            val snippet =
                if (linkLines.isEmpty()) node.summary else node.summary + "\n" + linkLines
            return SearchHit(
                path = fused.key,
                title = node.title,
                snippet = snippet,
                score = fused.score,
                channels = fused.channels,
                source = MemorySource.valueOf(node.source),
            )
        }
    }
