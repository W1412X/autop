/**
 * `retriever.keyword` 通道（ADR-0028）：查询文本中出现的检索词越多、越长，分越高。
 *
 * 检索词 = 节点的关键词 + 标题 + 路径最后一段（标题与路径名视为隐含关键词）。
 * 不分词：检索词作为子串出现在查询中即命中，得分为该检索词的字符数。
 * 查询本身是某个检索词的一部分时（如"张三"命中"张三丰"）按半权计分。
 * 过短的检索词不参与匹配（长度下限见 `memory.search`）。
 */
package com.example.autop.memory.impl.retriever

import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.MemoryConfig
import com.example.autop.core.config.MemorySearchSection
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemoryRetriever
import com.example.autop.memory.RetrievalHit
import com.example.autop.memory.impl.MemoryKeywords
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.db.SearchRow
import com.example.autop.memory.impl.decodeList
import com.example.autop.memory.impl.isWithinPath
import javax.inject.Inject
import javax.inject.Singleton

/** 关键词通道。 */
@Singleton
public class KeywordRetriever
    @Inject
    constructor(
        private val db: MemoryDb,
        private val configProvider: ConfigProvider,
    ) : MemoryRetriever {
        override val id: String = ID

        override suspend fun search(
            query: String,
            prefix: MemoryPath?,
            limit: Int,
        ): List<RetrievalHit> {
            val text = MemoryKeywords.normalize(query)
            if (text.isEmpty()) return emptyList()
            val cfg = configProvider.current()[MemoryConfig.SEARCH]
            return db
                .nodeDao()
                .searchRows()
                .asSequence()
                .filter { prefix == null || it.path.isWithinPath(prefix.value) }
                .mapNotNull { row -> scoreOf(text, row, cfg)?.let { score -> row.path to score } }
                .sortedByDescending { it.second }
                .take(limit)
                .mapNotNull { (path, score) -> MemoryPath.parse(path).getOrNull()?.let { RetrievalHit(it, score) } }
                .toList()
        }

        /** 节点在查询文本中的得分；未命中返回 null。 */
        internal fun scoreOf(
            text: String,
            row: SearchRow,
            cfg: MemorySearchSection,
        ): Double? {
            val terms =
                (decodeList(row.keywordsJson) + row.title + row.path.substringAfterLast('/'))
                    .map(MemoryKeywords::normalize)
                    .filter { MemoryKeywords.isUsable(it, cfg.minCjkChars, cfg.minAsciiChars) }
                    .distinct()
            var score = 0.0
            for (term in terms) {
                if (text.contains(term)) score += term.length
            }
            val queryIsUsable = MemoryKeywords.isUsable(text, cfg.minCjkChars, cfg.minAsciiChars)
            if (queryIsUsable && terms.any { it.contains(text) }) score += text.length * PARTIAL_WEIGHT
            return score.takeIf { it > 0.0 }
        }

        public companion object {
            public const val ID: String = "retriever.keyword"

            /** 查询是检索词的子串时的权重。 */
            private const val PARTIAL_WEIGHT: Double = 0.5
        }
    }
