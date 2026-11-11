/**
 * FTS4 检索通道（docs/tech/04 §2.5，ADR-0005）：`indexer.fts` 用 `BigramTokenizer`
 * 分词写 `thread_event_fts`；`recall.fts` 以 MATCH AND 查询，0 结果退化为 OR。
 */
package com.example.autop.agent.store.recall

import com.example.autop.agent.RecallCandidate
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadEventIndexer
import com.example.autop.agent.ThreadRecallRetriever
import com.example.autop.agent.store.db.FtsDao
import com.example.autop.agent.store.db.ThreadEventFtsEntity
import com.example.autop.core.common.BigramTokenizer
import com.example.autop.core.common.ThreadId

/** FTS 索引器。 */
public class FtsIndexer(
    private val fts: FtsDao,
    private val snippets: SnippetReader,
) : ThreadEventIndexer {
    override val id: String = "indexer.fts"

    override fun accepts(event: ThreadEvent): Boolean = true

    override suspend fun index(
        event: ThreadEvent,
        text: String,
    ) {
        fts.insert(
            ThreadEventFtsEntity(
                threadId = event.threadId.value,
                seq = event.seq,
                tokens = BigramTokenizer.joinForIndex(text),
            ),
        )
    }

    override suspend fun deleteThread(threadId: ThreadId) {
        fts.deleteOwned(threadId.value)
    }

    override suspend fun deleteEvents(seqs: List<Long>) {
        fts.deleteBySeqs(seqs)
    }

    override suspend fun rebuild(
        threadId: ThreadId,
        events: List<ThreadEvent>,
    ) {
        deleteThread(threadId)
        for (event in events) {
            val text = textOf(event) ?: continue
            index(event, text)
        }
    }

    private fun textOf(event: ThreadEvent): String? = snippets.extract(event)
}

/** FTS 检索通道。 */
public class FtsRetriever(
    private val fts: FtsDao,
    private val snippets: SnippetReader,
) : ThreadRecallRetriever {
    override val id: String = "recall.fts"

    override suspend fun isAvailable(): Boolean = true

    override suspend fun search(
        threadId: ThreadId,
        query: String,
        limit: Int,
    ): List<RecallCandidate> {
        val terms = BigramTokenizer.tokenize(query)
        if (terms.isEmpty()) return emptyList()
        var rows = fts.match(threadId.value, terms.joinToString(" "), limit)
        if (rows.isEmpty()) {
            rows = fts.match(threadId.value, terms.joinToString(" OR "), limit)
        }
        return rows.mapIndexed { i, row ->
            RecallCandidate(seq = row.seq, rank = i, snippet = snippets.snippet(row.seq))
        }
    }
}
