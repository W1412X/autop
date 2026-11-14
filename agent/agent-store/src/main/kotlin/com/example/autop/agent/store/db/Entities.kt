/**
 * `threads.db` 实体（docs/tech/04 §4，ADR-0021）：分支（线程）、事件树、FTS 与向量索引。
 */
package com.example.autop.agent.store.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 分支行。历史是从 [headSeq] 沿 `parentSeq` 回溯到根的事件路径；分叉时 [headSeq] 初始为
 * 分叉点事件，不复制任何事件（ADR-0021）。
 */
@Entity(tableName = "threads")
public data class ThreadEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** 分叉来源分支；顶层分支为 null。 */
    val parentThreadId: String?,
    /** 分叉点事件的 seq；顶层分支为 null。 */
    val forkSeq: Long?,
    /** 分叉标签（`1`、`1.2`）；顶层分支为 null。 */
    val forkLabel: String?,
    /** 会话根分支的 id（顶层分支即自身）。 */
    val rootId: String,
    /** 分支末端事件的 seq；空分支为 null。 */
    val headSeq: Long?,
    val archived: Boolean,
    val lastRunStatus: String?,
    /** 列表展示：最后一条用户输入前 60 字。 */
    val preview: String?,
    /** 会话级模型覆盖（ModelProfile id）；null 表示跟随全局默认。 */
    val modelProfileId: String?,
    /** 线程来源（ThreadOrigin 的名字）：对话列表只显示 CHAT（ADR-0031）。 */
    val origin: String = "CHAT",
)

/**
 * 事件行。[seq] 在整个库内全局唯一；[parentSeq] 是同一分支上的前一个事件，构成树。
 * [pending] 与 [forkable] 在追加时由父事件推导并落库，存储层、时间线与 UI 共用同一标志。
 */
@Entity(
    tableName = "thread_events",
    indices = [Index("parentSeq"), Index("threadId"), Index("runId")],
)
public data class ThreadEventEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    /** 创建该事件的分支（归属，用于文件与索引清理；路径查询不看它）。 */
    val threadId: String,
    val parentSeq: Long?,
    val runId: String?,
    val type: String,
    val v: Int,
    /** payload JSON；超过阈值时为 `@file:<ref>`。 */
    val payload: String,
    val createdAt: Long,
    /** 路径上未配对的工具调用数。 */
    val pending: Int,
    /** 可否在此分叉：路径上无未配对调用，且为助手消息或工具结果。 */
    val forkable: Boolean,
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
    primaryKeys = ["seq", "chunk", "modelId"],
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
