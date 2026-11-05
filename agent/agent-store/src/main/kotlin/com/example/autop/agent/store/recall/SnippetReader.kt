/**
 * 检索命中摘要（docs/tech/04 §2.5）：按 seq 回读事件并用 RecallTextExtractor
 * 取文本，截断到 `agent.recall.snippetChars`。
 */
package com.example.autop.agent.store.recall

import com.example.autop.agent.RecallTextExtractor
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.store.PAYLOAD_FILE_PREFIX
import com.example.autop.agent.store.PayloadCodec
import com.example.autop.agent.store.ThreadFiles
import com.example.autop.agent.store.db.EventDao
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigProvider

/** 事件文本提取与摘要读取。 */
public class SnippetReader(
    private val events: EventDao,
    private val files: ThreadFiles,
    private val config: ConfigProvider,
) {
    /** 索引文本：由 [RecallTextExtractor] 统一提取（重建索引时用）。 */
    fun extract(event: ThreadEvent): String? =
        RecallTextExtractor.extract(event, config.current()[AgentConfig.STORE].ftsToolResultChars)

    suspend fun snippet(
        threadId: ThreadId,
        seq: Long,
        runId: RunId? = null,
    ): String? {
        val row = events.bySeq(threadId.value, seq) ?: return null
        val payload = PayloadCodec.decode(row.type, row.v, readPayload(row.payload))
        val event = ThreadEvent(threadId, seq, runId, row.createdAt, payload)
        val cfg = config.current()
        return extract(event)?.take(cfg[AgentConfig.RECALL].snippetChars)
    }

    private fun readPayload(column: String): String =
        if (column.startsWith(PAYLOAD_FILE_PREFIX)) {
            files.readText(column.removePrefix(PAYLOAD_FILE_PREFIX))
        } else {
            column
        }
}
