/**
 * 记忆领域模型（docs/tech/06 §1）：节点、写入请求、版本与检索命中。
 *
 * 字段分工（ADR-0028）：`keywords` 只用于检索（关键词通道与向量输入）；`summary` 用于自动注入与
 * 列表展示；`content` 只在 `memory_read` 时返回。
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

    /** 流程（自动化）写入，行为同 agent：不覆盖用户内容、可撤销、可确认。 */
    FLOW,
}

/** 自动写入来源（agent 或流程）：受用户内容保护、可确认、可撤销。 */
public val MemorySource.isAutomated: Boolean
    get() = this == MemorySource.AGENT || this == MemorySource.FLOW

/**
 * 条目的向量化状态（ADR-0028）：按当前 embedding 模型与条目版本判断，向量记录的版本等于条目版本才算已向量化。
 */
@kotlinx.serialization.Serializable
public enum class VectorStatus {
    /** 当前版本已计算向量。 */
    VECTORIZED,

    /** 当前版本尚未计算向量（排队中，或计算失败等待下次写入/重建）。 */
    PENDING,

    /** 未配置 embedding 模型，不做向量化。 */
    DISABLED,
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
    /** 检索关键词（规范化后的小写形式）。 */
    val keywords: List<String>,
    val links: List<MemoryPath>,
    val source: MemorySource,
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long,
    /** 直接子条目数（查询时计算）。 */
    val childCount: Int,
    /** 当前版本的向量化状态（由 store 查询时填充）。 */
    val vectorStatus: VectorStatus = VectorStatus.DISABLED,
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

/**
 * 一次写入请求。`keywords` 为 null 表示保留原值（UPDATE/APPEND），空列表表示清空。
 */
public data class MemoryWrite(
    val path: MemoryPath,
    val mode: WriteMode,
    val title: String? = null,
    val summary: String? = null,
    val content: String,
    val keywords: List<String>? = null,
    val links: List<MemoryPath>? = null,
    val source: MemorySource,
    val runId: RunId? = null,
    val reason: String? = null,
    val evidence: MemoryEvidence? = null,
    /** CREATE 时跳过同名条目检查（确实是另一个对象时才传）。仅对 agent 写入生效。 */
    val forceNew: Boolean = false,
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

/** 单个检索通道对一条命中的贡献（综合分 = Σ weight × normalized）。 */
public data class ChannelScore(
    val channelId: String,
    /** 通道内原始分值（关键词：匹配字符数；向量：余弦相似度）。 */
    val raw: Double,
    /** 通道内最高原始分归一化后的分值（0–1）。 */
    val normalized: Double,
    /** 配置中的通道权重。 */
    val weight: Double,
)

/** 检索命中。 */
public data class SearchHit(
    val path: MemoryPath,
    val title: String,
    /** 摘要（用于注入与列表展示），附关联条目的一行摘要。 */
    val snippet: String,
    /** 综合分（各通道加权归一化分之和）。 */
    val score: Double,
    val channels: Set<String>,
    /** 条目来源（USER/AGENT/FLOW/IMPORT），供读工具区分是谁写的。 */
    val source: MemorySource,
    /** 各通道的得分明细（调试与工具输出用）。 */
    val scores: List<ChannelScore> = emptyList(),
)
