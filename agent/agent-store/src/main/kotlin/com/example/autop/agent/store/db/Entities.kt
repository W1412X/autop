/**
 * `threads.db` 实体（docs/tech/04 §4）：线程、事件、Baseline 指针、FTS 与向量索引。
 */
package com.example.autop.agent.store.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/** 线程行。 */
@Entity(tableName = "threads")
public data class ThreadEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val parentThreadId: String?,
    val parentSeq: Long?,
    val lastSeq: Long,
    val archived: Boolean,
    val lastRunStatus: String?,
    /** 列表展示：最后一条用户输入前 60 字。 */
    val preview: String?,
)

/**
 * 线程事件行：[payload] 为 EventPayload JSON；超过 `agent.store.inlineMaxChars` 时
 * 落文件，这里存 `"@file:<ref>"`。
 */
@Entity(
    tableName = "thread_events",
    primaryKeys = ["threadId", "seq"],
    indices = [Index("threadId", "type"), Index("runId")],
)
public data class ThreadEventEntity(
    val threadId: String,
    val seq: Long,
    val runId: String?,
    val type: String,
    val v: Int,
    val payload: String,
    val createdAt: Long,
)

/** 最近一条 Baseline 的 seq，加速 `eventsSinceLatestBaseline`。 */
@Entity(tableName = "thread_latest_baseline")
public data class LatestBaselineEntity(
    @PrimaryKey val threadId: String,
    val seq: Long,
)

/** FTS4 索引行：[tokens] 为 bigram 词元（空格连接，ADR-0005）。 */
@Fts4(notIndexed = ["threadId", "seq"])
@Entity(tableName = "thread_event_fts")
public data class ThreadEventFtsEntity(
    val threadId: String,
    val seq: Long,
    val tokens: String,
)

/** 向量索引行：[vector] 为 float32 小端字节。 */
@Entity(
    tableName = "thread_event_vectors",
    primaryKeys = ["threadId", "seq", "chunk", "modelId"],
)
public data class ThreadEventVectorEntity(
    val threadId: String,
    val seq: Long,
    val chunk: Int,
    val modelId: String,
    val dim: Int,
    val vector: ByteArray,
    val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is ThreadEventVectorEntity &&
            other.threadId == threadId &&
            other.seq == seq &&
            other.chunk == chunk &&
            other.modelId == modelId &&
            other.dim == dim &&
            other.vector.contentEquals(vector) &&
            other.createdAt == createdAt

    override fun hashCode(): Int {
        var result = threadId.hashCode()
        result = 31 * result + seq.hashCode()
        result = 31 * result + chunk
        result = 31 * result + modelId.hashCode()
        result = 31 * result + dim
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
