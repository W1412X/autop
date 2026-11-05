/**
 * `retriever.fts` 通道（docs/tech/06 §2.6）：`memory_fts MATCH`，
 * query 用 [BigramTokenizer] 切词后 AND 连接，0 结果时退化为 OR 再查一次。
 *
 * 注：bigram 分词下 FTS4 的 snippet() 输出的是词元拼接（可读性差），
 * 故 snippet 由融合层用节点 summary 兜底（见 `HybridMemorySearch`）。
 */
package com.example.autop.memory.impl.retriever

import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.autop.core.common.BigramTokenizer
import com.example.autop.core.common.getOrNull
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemoryRetriever
import com.example.autop.memory.RankedPath
import com.example.autop.memory.impl.FtsRanker
import com.example.autop.memory.impl.db.FtsMatchRow
import com.example.autop.memory.impl.db.MemoryDb
import javax.inject.Inject
import javax.inject.Singleton

/** FTS4 全文通道。 */
@Singleton
public class FtsRetriever
    @Inject
    constructor(
        private val db: MemoryDb,
    ) : MemoryRetriever {
        override val id: String = ID

        override suspend fun search(
            query: String,
            prefix: MemoryPath?,
            limit: Int,
        ): List<RankedPath> {
            val tokens = BigramTokenizer.tokenize(query).map { "\"$it\"" }
            if (tokens.isEmpty()) return emptyList()
            val prefixValue = prefix?.value.orEmpty()
            val rows =
                match(tokens.joinToString(separator = " AND "), prefixValue)
                    .ifEmpty { match(tokens.joinToString(separator = " OR "), prefixValue) }
            return FtsRanker.rank(rows).take(limit).mapIndexed { i, row ->
                RankedPath(
                    path = MemoryPath.parse(row.path).getOrNull() ?: MemoryPath.ROOT,
                    rank = i + 1,
                    snippet = null,
                )
            }
        }

        private suspend fun match(
            matchQuery: String,
            prefix: String,
        ): List<FtsMatchRow> =
            db.ftsDao().match(
                SimpleSQLiteQuery(
                    "SELECT path, length(titleTokens) AS titleLen, length(bodyTokens) AS bodyLen, " +
                        "matchinfo(memory_fts, 'pcnalx') AS info " +
                        "FROM memory_fts WHERE memory_fts MATCH ? AND path LIKE ? || '%' LIMIT $MATCH_LIMIT",
                    arrayOf(matchQuery, prefix),
                ),
            )

        private companion object {
            const val ID: String = "retriever.fts"
            const val MATCH_LIMIT: Int = 200
        }
    }
