/**
 * 记忆领域模型（docs/tech/06 §1）：节点、写入请求、版本与检索命中。
 */
package com.example.autop.memory

import com.example.autop.core.common.RunId

/** 记忆条目来源。 */
@kotlinx.serialization.Serializable
public enum class MemorySource {
    /** 用户在记忆页手工写入（或确认过的）。 */
    USER,

    /** agent 写入。 */
    AGENT,

    /** 导入恢复。 */
    IMPORT,
}

/** 记忆条目（目录也是一种条目：content 为空）。 */
@kotlinx.serialization.Serializable
public data class MemoryNode(
    val path: MemoryPath,
    val title: String,
    /** ≤ `memory.summary.maxChars`。 */
    val summary: String,
    /** Markdown 正文。 */
    val content: String,
    val tags: List<String>,
    val links: List<MemoryPath>,
    val source: MemorySource,
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long,
    /** 直接子条目数（查询时计算）。 */
    val childCount: Int,
)

/** 写入证据：记的是从哪里得知（包名与摘录）。 */
@kotlinx.serialization.Serializable
public data class MemoryEvidence(
    val packageName: String?,
    val excerpt: String,
)

/** 写入模式。 */
public enum class WriteMode {
    CREATE,
    UPDATE,
    APPEND,
}

/** 实际执行的写入模式。 */
public enum class AppliedMode {
    CREATED,
    UPDATED,
    APPENDED,

    /** 保护用户内容：agent 的 UPDATE 被降级为补充段落。 */
    APPENDED_AS_SUPPLEMENT,
}

/** 一次写入请求。 */
public data class MemoryWrite(
    val path: MemoryPath,
    val mode: WriteMode,
    val title: String? = null,
    val summary: String? = null,
    val content: String,
    val tags: List<String>? = null,
    val links: List<MemoryPath>? = null,
    val source: MemorySource,
    val runId: RunId? = null,
    val reason: String? = null,
    val evidence: MemoryEvidence? = null,
)

/** 写入结果。 */
public data class WriteResult(
    val node: MemoryNode,
    val versionId: Long,
    val appliedMode: AppliedMode,
)

/** 变化类型。 */
@kotlinx.serialization.Serializable
public enum class ChangeType {
    CREATE,
    UPDATE,
    APPEND,
    SUPPLEMENT,
    DELETE,
    MOVE,
    REVERT,
    CONFIRM,
}

/** 一个历史版本（记录前后值，供撤销）。 */
@kotlinx.serialization.Serializable
public data class MemoryVersion(
    val id: Long,
    val path: MemoryPath,
    val version: Int,
    val changeType: ChangeType,
    val titleBefore: String?,
    val titleAfter: String?,
    val contentBefore: String?,
    val contentAfter: String?,
    val source: MemorySource,
    val runId: RunId?,
    val reason: String?,
    val evidence: MemoryEvidence?,
    val createdAt: Long,
)

/** 检索命中。 */
public data class SearchHit(
    val path: MemoryPath,
    val title: String,
    val snippet: String,
    val score: Double,
    val channels: Set<String>,
)

/** 单通道排名项。 */
public data class RankedPath(
    val path: MemoryPath,
    val rank: Int,
    val snippet: String?,
)
