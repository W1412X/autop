/**
 * `retriever.path` 通道（docs/tech/06 §2.6）：query 按空白切词，与标题、路径各段比较：
 * 完全相等 3 分、前缀 2 分、包含 1 分，按总分排序。
 */
package com.example.autop.memory.impl.retriever

import com.example.autop.core.common.getOrNull
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemoryRetriever
import com.example.autop.memory.RankedPath
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.db.TitleRow
import javax.inject.Inject
import javax.inject.Singleton

/** 路径/标题匹配通道。 */
@Singleton
public class PathRetriever
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
            val words = query.split(WHITESPACE).filter { it.isNotBlank() }
            if (words.isEmpty()) return emptyList()
            return db
                .nodeDao()
                .allTitles()
                .asSequence()
                .filter { prefix == null || it.path.startsWith(prefix.value) }
                .map { row -> row to scoreWords(words, row.path, row.title) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<TitleRow, Int>> { it.second }.thenBy { it.first.path })
                .take(limit)
                .mapIndexed { i, (row, _) ->
                    RankedPath(
                        path = MemoryPath.parse(row.path).getOrNull() ?: MemoryPath.ROOT,
                        rank = i + 1,
                        snippet = row.title,
                    )
                }.toList()
        }

        private fun scoreWords(
            words: List<String>,
            path: String,
            title: String,
        ): Int {
            val segments = path.split('/').filter { it.isNotEmpty() }
            return words.sumOf { w ->
                when {
                    title.equals(w, ignoreCase = true) -> SCORE_EXACT
                    segments.any { it.equals(w, ignoreCase = true) } -> SCORE_EXACT
                    title.startsWith(w, ignoreCase = true) -> SCORE_PREFIX
                    segments.any { it.startsWith(w, ignoreCase = true) } -> SCORE_PREFIX
                    title.contains(w, ignoreCase = true) -> SCORE_CONTAINS
                    path.contains(w, ignoreCase = true) -> SCORE_CONTAINS
                    else -> 0
                }
            }
        }

        private companion object {
            const val ID: String = "retriever.path"
            const val SCORE_EXACT: Int = 3
            const val SCORE_PREFIX: Int = 2
            const val SCORE_CONTAINS: Int = 1
            private val WHITESPACE = Regex("\\s+")
        }
    }
