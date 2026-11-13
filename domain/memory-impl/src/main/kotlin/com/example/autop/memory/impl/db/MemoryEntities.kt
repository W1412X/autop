/**
 * `memory.db` 实体（docs/tech/06 §2.1，ADR-0028）：节点、版本、向量块。
 * 检索不再使用 FTS 表；关键词存在节点的 `keywordsJson` 列中。
 */
package com.example.autop.memory.impl.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 记忆节点（目录与条目同表）。 */
@Entity(
    tableName = "memory_nodes",
    indices = [Index("parentPath"), Index("updatedAt")],
)
public data class MemoryNodeEntity(
    @PrimaryKey val path: String,
    val parentPath: String?,
    val title: String,
    val summary: String,
    val content: String,
    /** 规范化后的关键词，JSON 数组。 */
    val keywordsJson: String,
    val linksJson: String,
    val source: String,
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val accessCount: Int,
    val lastAccessAt: Long?,
)

/** 记忆版本（撤销的依据）。 */
@Entity(
    tableName = "memory_versions",
    indices = [Index("path"), Index("runId"), Index("createdAt")],
)
public data class MemoryVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val version: Int,
    val changeType: String,
    val titleBefore: String?,
    val titleAfter: String?,
    val contentBefore: String?,
    val contentAfter: String?,
    /** 变更前的关键词（JSON 数组），撤销时恢复用；创建时为 null。 */
    val keywordsBefore: String? = null,
    val source: String,
    val runId: String?,
    val reason: String?,
    val evidenceJson: String?,
    val createdAt: Long,
)

/**
 * 向量块（float32 小端字节）。一个节点只有一个向量（chunk 恒为 0），输入为"标题 + 关键词"。
 */
@Entity(
    tableName = "memory_vectors",
    primaryKeys = ["path", "chunk", "modelId"],
    indices = [Index("modelId")],
)
public data class MemoryVectorEntity(
    val path: String,
    val chunk: Int,
    val modelId: String,
    val dim: Int,
    val vector: ByteArray,
    val textHash: String,
    /** 计算此向量时条目的版本；与条目当前版本不等即为"待向量化"（按版本的向量化状态）。 */
    val nodeVersion: Int,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is MemoryVectorEntity &&
            other.path == path &&
            other.chunk == chunk &&
            other.modelId == modelId &&
            other.dim == dim &&
            other.textHash == textHash &&
            other.nodeVersion == nodeVersion

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + chunk
        result = 31 * result + modelId.hashCode()
        result = 31 * result + dim
        result = 31 * result + textHash.hashCode()
        result = 31 * result + nodeVersion
        return result
    }
}
