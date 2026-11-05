/**
 * 线程实体 ↔ 事件映射（docs/tech/04 §4）：大 payload 落文件的存取、事件/摘要
 * 转换、列表预览字段计算与分叉点判定（04 §3.11 的纯逻辑）。
 */
package com.example.autop.agent.store

import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.EventPayload
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadSummary
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.store.db.ThreadEntity
import com.example.autop.agent.store.db.ThreadEventEntity
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigProvider
import com.example.autop.llm.Block

/**
 * 实体映射与线程文件存取（docs/tech/04 §4）：大 payload 落文件的存取、事件/摘要
 * 转换、列表预览字段计算与分叉点判定（04 §3.11 的纯逻辑），并透传 [ThreadFiles]
 * 的 blob 存取与线程目录清理。
 */
public class ThreadMappers(
    private val files: ThreadFiles,
    private val config: ConfigProvider,
) {
    /** 大 payload 落文件，返回 payload 列值（docs/tech/04 §4）。 */
    fun storePayload(
        threadId: ThreadId,
        seq: Long,
        json: String,
    ): String {
        val inlineMax = config.current()[AgentConfig.STORE].inlineMaxChars
        return if (json.length > inlineMax) {
            PAYLOAD_FILE_PREFIX + files.writePayload(threadId.value, seq, json)
        } else {
            json
        }
    }

    /** payload 列值还原为 JSON（`@file:` 前缀表示落文件）。 */
    fun readPayload(column: String): String =
        if (column.startsWith(PAYLOAD_FILE_PREFIX)) {
            files.readText(column.removePrefix(PAYLOAD_FILE_PREFIX))
        } else {
            column
        }

    fun toEvent(entity: ThreadEventEntity): ThreadEvent =
        ThreadEvent(
            threadId = ThreadId(entity.threadId),
            seq = entity.seq,
            runId = entity.runId?.let(::RunId),
            createdAt = entity.createdAt,
            payload = PayloadCodec.decode(entity.type, entity.v, readPayload(entity.payload)),
        )

    fun toSummary(entity: ThreadEntity): ThreadSummary =
        ThreadSummary(
            threadId = ThreadId(entity.id),
            title = entity.title,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            preview = entity.preview,
            lastRunStatus = entity.lastRunStatus?.let { runCatching { RunOutcome.valueOf(it) }.getOrNull() },
        )

    /** 列表预览：最后一条用户输入前 [PREVIEW_CHARS] 字（INJECTED 不算输入）。 */
    fun previewOf(payload: EventPayload): String? {
        val message = payload as? UserMessage
        return if (message == null || message.kind == UserMessageKind.INJECTED) {
            null
        } else {
            message.blocks
                .filterIsInstance<Block.Text>()
                .firstOrNull()
                ?.text
                ?.take(PREVIEW_CHARS)
        }
    }

    /** blob 存取透传（docs/tech/04 §4）。 */
    fun saveBlob(
        threadId: ThreadId,
        name: String,
        bytes: ByteArray,
    ): String = files.saveBlob(threadId.value, name, bytes)

    /** 线程文件目录清理透传。 */
    fun deleteThread(threadId: ThreadId) {
        files.deleteThread(threadId.value)
    }

    fun runStatusOf(payload: EventPayload): String? = (payload as? RunFinished)?.status?.let { it.name }

    /** 可分叉的事件点：无未配对 ToolCall 的 ASSISTANT / TOOL 批次结束处（docs/tech/04 §3.11）。 */
    fun forkablePoints(events: List<ThreadEvent>): List<Long> {
        val open = mutableSetOf<String>()
        val points = mutableListOf<Long>()
        events.forEachIndexed { i, event ->
            when (val payload = event.payload) {
                is AssistantMessage ->
                    payload.blocks.filterIsInstance<Block.ToolCall>().forEach { open += it.id }
                is ToolResultEvent -> open -= payload.callId
                else -> Unit
            }
            if (event.payload is AssistantMessage || event.payload is ToolResultEvent) {
                val next = events.getOrNull(i + 1)?.payload
                val batchEnd = next !is AssistantMessage && next !is ToolResultEvent
                if (batchEnd && open.isEmpty()) points += event.seq
            }
        }
        return points
    }

    private companion object {
        /** 列表预览字数（docs/tech/04 §4）。 */
        const val PREVIEW_CHARS = 60
    }
}
