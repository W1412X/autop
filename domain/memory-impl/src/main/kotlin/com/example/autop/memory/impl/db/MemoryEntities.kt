/**
 * `memory.db` 实体（docs/tech/06 §2.1）：节点、版本、FTS4 分词列、向量块。
 */
package com.example.autop.memory.impl.db

import androidx.room.Entity
import androidx.room.Fts4
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
    val tagsJson: String,
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
    val source: String,
    val runId: String?,
    val reason: String?,
    val evidenceJson: String?,
    val createdAt: Long,
)

/** FTS4 分词索引（应用侧 bigram 分词，见 `BigramTokenizer`）。 */
@Fts4(notIndexed = ["path"])
@Entity(tableName = "memory_fts")
public data class MemoryFtsEntity(
    val path: String,
    val titleTokens: String,
    val bodyTokens: String,
)

/** 向量块（float32 小端字节）。 */
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
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is MemoryVectorEntity &&
            other.path == path &&
            other.chunk == chunk &&
            other.modelId == modelId &&
            other.dim == dim &&
            other.textHash == textHash

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + chunk
        result = 31 * result + modelId.hashCode()
        result = 31 * result + dim
        result = 31 * result + textHash.hashCode()
        return result
    }
}
