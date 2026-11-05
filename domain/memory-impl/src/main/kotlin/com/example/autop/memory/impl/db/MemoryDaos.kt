/**
 * `memory.db` DAO（docs/tech/06 §2）。FTS 匹配用 `@RawQuery`，AND→OR 退化由调用方换查询串。
 */
package com.example.autop.memory.impl.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery

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

    @Query("SELECT path FROM memory_nodes WHERE path LIKE :prefix || '%'")
    public suspend fun pathsUnder(prefix: String): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insert(node: MemoryNodeEntity)

    /** 幂等插入（已存在则忽略）：播种与自动建父目录用，避免并发 check-then-insert 撞主键。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertIfAbsent(node: MemoryNodeEntity)

    @Update
    public suspend fun update(node: MemoryNodeEntity)

    @Query("DELETE FROM memory_nodes WHERE path = :path")
    public suspend fun delete(path: String)

    @Query("DELETE FROM memory_nodes WHERE path LIKE :prefix || '%'")
    public suspend fun deleteUnder(prefix: String): Int

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

    @Query("SELECT path, title FROM memory_nodes")
    public suspend fun allTitles(): List<TitleRow>

    @Query("SELECT path FROM memory_nodes")
    public suspend fun allPaths(): List<String>
}

/** 轻量行：path + title（路径匹配通道用）。 */
public data class TitleRow(
    val path: String,
    val title: String,
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

/** FTS4 表访问。 */
@Dao
public interface MemoryFtsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entry: MemoryFtsEntity)

    @Query("DELETE FROM memory_fts WHERE path = :path")
    public suspend fun delete(path: String)

    @Query("DELETE FROM memory_fts WHERE path LIKE :prefix || '%'")
    public suspend fun deleteUnder(prefix: String)

    /** 任意 MATCH 查询串（AND 或 OR 组合），返回命中行与 matchinfo（'pcnalx'）供 [FtsRanker] 评分。 */
    @RawQuery
    public suspend fun match(query: SupportSQLiteQuery): List<FtsMatchRow>

    @Query("SELECT * FROM memory_fts WHERE path = :path")
    public suspend fun byPath(path: String): MemoryFtsEntity?
}

/** FTS 匹配行：命中文档的列长度与 matchinfo('pcnalx') 原始块，供 [FtsRanker] 解析评分。 */
public data class FtsMatchRow(
    val path: String,
    val titleLen: Int,
    val bodyLen: Int,
    val info: ByteArray,
)

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

    @Query("DELETE FROM memory_vectors WHERE path = :path")
    public suspend fun deleteByPath(path: String)

    @Query("DELETE FROM memory_vectors WHERE modelId = :modelId")
    public suspend fun deleteByModel(modelId: String)

    @Query("DELETE FROM memory_vectors WHERE modelId != :modelId")
    public suspend fun deleteExcept(modelId: String)
}
