/**
 * 混合检索（ADR-0028）：按 `memory.search.channels` 并发执行各通道，每个通道的原始分值先按
 * 通道内最大值归一化，再按 `memory.search.weights` 加权求和。每条命中保留各通道的得分明细。
 *
 * 另提供 [prefetch]：goal 预取只走关键词通道，原始分值须达到下限，且跳过内置根目录与空条目。
 */
package com.example.autop.memory.impl

import com.example.autop.core.common.Clock
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.MemoryConfig
import com.example.autop.memory.ChannelScore
import com.example.autop.memory.ChannelStatus
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemoryRetriever
import com.example.autop.memory.MemorySearch
import com.example.autop.memory.MemorySource
import com.example.autop.memory.SearchHit
import com.example.autop.memory.SearchReport
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.retriever.KeywordRetriever
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/** 混合检索。 */
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
        ): List<SearchHit> = searchReport(query, prefix, k).hits

        override suspend fun searchReport(
            query: String,
            prefix: MemoryPath?,
            k: Int,
        ): SearchReport {
            val cfg = configProvider.current()[MemoryConfig.SEARCH]
            val byId = retrievers.associateBy { it.id }
            val channelResults =
                coroutineScope {
                    cfg.channels
                        .map { channelId ->
                            async {
                                val retriever = byId[channelId]
                                val hits =
                                    if (retriever != null && retriever.available) {
                                        retriever.search(query, prefix, cfg.perChannelLimit)
                                    } else {
                                        emptyList()
                                    }
                                channelId to hits
                            }
                        }.awaitAll()
                }
            val channels = mutableListOf<ChannelStatus>()
            val merged = LinkedHashMap<MemoryPath, Merged>()
            for ((channelId, hits) in channelResults) {
                channels += ChannelStatus(channelId, byId[channelId]?.available == true, hits.size)
                val max = hits.maxOfOrNull { it.score }
                if (max == null || max <= 0.0) continue
                val weight = cfg.weights[channelId] ?: DEFAULT_WEIGHT
                for (hit in hits) {
                    val entry = merged.getOrPut(hit.path) { Merged() }
                    val normalized = hit.score / max
                    entry.score += weight * normalized
                    entry.channels += channelId
                    entry.scores += ChannelScore(channelId, raw = hit.score, normalized = normalized, weight = weight)
                }
            }
            val ranked = merged.entries.sortedByDescending { it.value.score }
            val out = mutableListOf<SearchHit>()
            for ((path, entry) in ranked) {
                if (out.size >= k) break
                buildHit(path, entry.score, entry.channels, entry.scores)?.let { out += it }
            }
            return SearchReport(hits = out, channels = channels, candidateCount = merged.size)
        }

        override suspend fun prefetch(
            query: String,
            k: Int,
            minKeywordScore: Double,
        ): List<SearchHit> {
            val keyword = retrievers.firstOrNull { it.id == KeywordRetriever.ID } ?: return emptyList()
            val cfg = configProvider.current()[MemoryConfig.SEARCH]
            val eligible =
                keyword
                    .search(query, prefix = null, limit = cfg.perChannelLimit)
                    .filter { it.score >= minKeywordScore && !it.path.isProtectedRoot() }
            val out = mutableListOf<SearchHit>()
            for (candidate in eligible) {
                if (out.size >= k) break
                buildHit(candidate.path, candidate.score, setOf(KeywordRetriever.ID), emptyList())
                    ?.takeIf { it.snippet.isNotBlank() }
                    ?.let { out += it }
            }
            return out
        }

        private suspend fun buildHit(
            path: MemoryPath,
            score: Double,
            channels: Set<String>,
            scores: List<ChannelScore>,
        ): SearchHit? {
            val node = db.nodeDao().get(path.value) ?: return null
            db.nodeDao().touch(node.path, clock.nowMillis())
            val linkLines =
                decodeList(node.linksJson)
                    .mapNotNull { db.nodeDao().get(it) }
                    .joinToString(separator = "\n") { "  - ${it.title}：${it.summary}" }
            return SearchHit(
                path = path,
                title = node.title,
                snippet = if (linkLines.isEmpty()) node.summary else node.summary + "\n" + linkLines,
                score = score,
                channels = channels,
                source = MemorySource.valueOf(node.source),
                scores = scores,
            )
        }

        private class Merged {
            var score: Double = 0.0
            val channels: MutableSet<String> = mutableSetOf()
            val scores: MutableList<ChannelScore> = mutableListOf()
        }

        private companion object {
            const val DEFAULT_WEIGHT: Double = 1.0
        }
    }
