/**
 * `threads.db` DAO（docs/tech/04 §4）。
 */
package com.example.autop.agent.store.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

/** 线程行读写。 */
@Dao
public interface ThreadDao {
    @Insert
    public suspend fun insert(thread: ThreadEntity)

    @Query("SELECT * FROM threads WHERE id = :id")
    public suspend fun byId(id: String): ThreadEntity?

    @Query(
        "SELECT * FROM threads WHERE archived = 0 " +
            "ORDER BY updatedAt DESC LIMIT :limit OFFSET :offset",
    )
    public suspend fun page(
        offset: Int,
        limit: Int,
    ): List<ThreadEntity>

    @Query("UPDATE threads SET title = :title WHERE id = :id")
    public suspend fun rename(
        id: String,
        title: String,
    )

    @Query(
        "UPDATE threads SET lastSeq = :lastSeq, updatedAt = :updatedAt, " +
            "preview = COALESCE(:preview, preview), " +
            "lastRunStatus = COALESCE(:lastRunStatus, lastRunStatus) " +
            "WHERE id = :id",
    )
    public suspend fun updateOnAppend(
        id: String,
        lastSeq: Long,
        updatedAt: Long,
        preview: String?,
        lastRunStatus: String?,
    )

    @Query("DELETE FROM threads WHERE id = :id")
    public suspend fun delete(id: String)
}

/** 事件行读写。 */
@Dao
public interface EventDao {
    @Insert
    public suspend fun insert(event: ThreadEventEntity)

    @Query(
        "SELECT * FROM thread_events WHERE threadId = :threadId " +
            "AND seq >= :fromSeq AND seq <= :toSeq ORDER BY seq ASC",
    )
    public suspend fun range(
        threadId: String,
        fromSeq: Long,
        toSeq: Long,
    ): List<ThreadEventEntity>

    @Query("SELECT * FROM thread_events WHERE threadId = :threadId AND seq = :seq")
    public suspend fun bySeq(
        threadId: String,
        seq: Long,
    ): ThreadEventEntity?

    @Query("DELETE FROM thread_events WHERE threadId = :threadId")
    public suspend fun deleteThread(threadId: String)
}

/** Baseline 指针。 */
@Dao
public interface BaselineDao {
    @Upsert
    public suspend fun upsert(entity: LatestBaselineEntity)

    @Query("SELECT seq FROM thread_latest_baseline WHERE threadId = :threadId")
    public suspend fun latestSeq(threadId: String): Long?

    @Query("DELETE FROM thread_latest_baseline WHERE threadId = :threadId")
    public suspend fun deleteThread(threadId: String)
}

/** FTS 查询结果投影。 */
public data class FtsRow(
    val threadId: String,
    val seq: Long,
    val tokens: String,
)

/** FTS4 索引读写。 */
@Dao
public interface FtsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insert(entity: ThreadEventFtsEntity)

    /** MATCH 查询：[match] 为 bigram 词元表达式（空格连接即 AND）。 */
    @Query(
        "SELECT threadId, seq, tokens FROM thread_event_fts " +
            "WHERE threadId = :threadId AND thread_event_fts MATCH :match " +
            "ORDER BY seq DESC LIMIT :limit",
    )
    public suspend fun match(
        threadId: String,
        match: String,
        limit: Int,
    ): List<FtsRow>

    @Query("DELETE FROM thread_event_fts WHERE threadId = :threadId")
    public suspend fun deleteThread(threadId: String)
}

/** 向量索引读写。 */
@Dao
public interface VectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insert(entity: ThreadEventVectorEntity)

    @Query("SELECT * FROM thread_event_vectors WHERE threadId = :threadId")
    public suspend fun allOfThread(threadId: String): List<ThreadEventVectorEntity>

    @Query("DELETE FROM thread_event_vectors WHERE threadId = :threadId")
    public suspend fun deleteThread(threadId: String)
}
