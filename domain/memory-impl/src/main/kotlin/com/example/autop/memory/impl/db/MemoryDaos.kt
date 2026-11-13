/**
 * `memory.db` DAO（docs/tech/06 §2，ADR-0028）。
 */
package com.example.autop.memory.impl.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** 节点表访问。 */
@Dao
@Suppress("TooManyFunctions") // DAO 是表的操作面，方法数随查询需求线性增长
public interface MemoryNodeDao {
    @Query("SELECT * FROM memory_nodes WHERE path = :path")
    public suspend fun get(path: String): MemoryNodeEntity?

    @Query(
        "SELECT * FROM memory_nodes WHERE parentPath = :parent " +
            "ORDER BY title LIMIT :limit OFFSET :offset",
    )
    public suspend fun children(
        parent: String,
        offset: Int,
        limit: Int,
    ): List<MemoryNodeEntity>

    @Query("SELECT COUNT(*) FROM memory_nodes WHERE parentPath = :parent")
    public suspend fun childCount(parent: String): Int

    @Query("SELECT * FROM memory_nodes WHERE parentPath = :parent ORDER BY title")
    public suspend fun childrenAll(parent: String): List<MemoryNodeEntity>

    /**
     * 子树（含自身）的全部路径。按路径段边界匹配，不用 LIKE：LIKE 中 `_` 与 `%` 是通配符，
     * 删除 `/people/张三` 不能连带 `/people/张三丰`。调用方不得传入根 "/"（根受保护）。
     */
    @Query(
        "SELECT path FROM memory_nodes WHERE path = :prefix " +
            "OR substr(path, 1, length(:prefix) + 1) = :prefix || '/'",
    )
    public suspend fun pathsUnder(prefix: String): List<String>

    /** 关键词通道的全量扫描输入（条目数在千级，整表读取后在内存中匹配）。 */
    @Query("SELECT path, title, summary, keywordsJson, length(content) AS contentLength FROM memory_nodes")
    public suspend fun searchRows(): List<SearchRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insert(node: MemoryNodeEntity)

    /** 幂等插入（已存在则忽略）：播种与自动建父目录用，避免并发 check-then-insert 撞主键。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertIfAbsent(node: MemoryNodeEntity)

    @Update
    public suspend fun update(node: MemoryNodeEntity)

    @Query("DELETE FROM memory_nodes WHERE path = :path")
    public suspend fun delete(path: String)

    @Query(
        "UPDATE memory_nodes SET path = :newPath, parentPath = :newParent WHERE path = :oldPath",
    )
    public suspend fun moveNode(
        oldPath: String,
        newPath: String,
        newParent: String?,
    )

    @Query("UPDATE memory_nodes SET accessCount = accessCount + 1, lastAccessAt = :now WHERE path = :path")
    public suspend fun touch(
        path: String,
        now: Long,
    )

    @Query("SELECT COUNT(*) FROM memory_nodes")
    public suspend fun count(): Int

    @Query("SELECT path FROM memory_nodes")
    public suspend fun allPaths(): List<String>
}

/** 关键词通道的扫描行。`contentLength` 只用于判断是否为空目录，不读正文。 */
public data class SearchRow(
    val path: String,
    val title: String,
    val summary: String,
    val keywordsJson: String,
    val contentLength: Int,
)

/** 条目向量的版本投影（[MemoryVectorDao.indexedVersions]）。 */
public data class IndexedVersion(
    val path: String,
    val nodeVersion: Int,
)

/** 版本表访问。 */
@Dao
public interface MemoryVersionDao {
    @Insert
    public suspend fun insert(version: MemoryVersionEntity): Long

    @Query("SELECT * FROM memory_versions WHERE id = :id")
    public suspend fun byId(id: Long): MemoryVersionEntity?

    @Query("SELECT * FROM memory_versions WHERE path = :path ORDER BY id DESC LIMIT :limit")
    public suspend fun byPath(
        path: String,
        limit: Int,
    ): List<MemoryVersionEntity>

    @Query("SELECT * FROM memory_versions WHERE runId = :runId ORDER BY createdAt DESC")
    public suspend fun byRun(runId: String): List<MemoryVersionEntity>

    @Query("SELECT * FROM memory_versions ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    public suspend fun recent(
        offset: Int,
        limit: Int,
    ): List<MemoryVersionEntity>

    @Query(
        "SELECT * FROM memory_versions WHERE (:source IS NULL OR source = :source) " +
            "ORDER BY createdAt DESC LIMIT :limit OFFSET :offset",
    )
    public suspend fun recentBySource(
        offset: Int,
        limit: Int,
        source: String?,
    ): List<MemoryVersionEntity>
}

/** 向量表访问。 */
@Dao
public interface MemoryVectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(vector: MemoryVectorEntity)

    @Query("SELECT * FROM memory_vectors WHERE modelId = :modelId")
    public suspend fun byModel(modelId: String): List<MemoryVectorEntity>

    @Query("SELECT * FROM memory_vectors WHERE path = :path AND modelId = :modelId")
    public suspend fun byPath(
        path: String,
        modelId: String,
    ): List<MemoryVectorEntity>

    /** 当前模型下每个条目（主块）的向量所对应的条目版本；不读向量 BLOB。 */
    @Query("SELECT path, nodeVersion FROM memory_vectors WHERE modelId = :modelId AND chunk = 0")
    public suspend fun indexedVersions(modelId: String): List<IndexedVersion>

    @Query("DELETE FROM memory_vectors WHERE path = :path")
    public suspend fun deleteByPath(path: String)

    @Query("DELETE FROM memory_vectors WHERE modelId = :modelId")
    public suspend fun deleteByModel(modelId: String)

    @Query("DELETE FROM memory_vectors WHERE modelId != :modelId")
    public suspend fun deleteExcept(modelId: String)
}
