/**
 * `threads.db` DAO（docs/tech/04 §4，ADR-0021）。
 *
 * 分支历史用递归 CTE 从头节点沿 `parentSeq` 回溯得到；所有读历史的查询都经过 [BRANCH_PATH_CTE]，
 * 因此祖先事件天然可见，分叉不需要复制。
 */
package com.example.autop.agent.store.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 分支路径：从 `:threadId` 的头节点沿 `parentSeq` 回溯，得到 `path(seq)`。拼在查询前使用。 */
internal const val BRANCH_PATH_CTE: String =
    "WITH RECURSIVE path(seq) AS (" +
        "SELECT headSeq FROM threads WHERE id = :threadId AND headSeq IS NOT NULL " +
        "UNION ALL " +
        "SELECT e.parentSeq FROM thread_events e JOIN path p ON e.seq = p.seq WHERE e.parentSeq IS NOT NULL" +
        ") "

/** 仍被某个分支路径引用的事件集合 `live(seq)`；不在其中的事件可以删除。 */
internal const val LIVE_EVENTS_CTE: String =
    "WITH RECURSIVE live(seq) AS (" +
        "SELECT headSeq FROM threads WHERE headSeq IS NOT NULL " +
        "UNION " +
        "SELECT e.parentSeq FROM thread_events e JOIN live l ON e.seq = l.seq WHERE e.parentSeq IS NOT NULL" +
        ") "

/** 分支行读写。 */
@Dao
public interface ThreadDao {
    @Insert
    public suspend fun insert(thread: ThreadEntity)

    @Query("SELECT * FROM threads WHERE id = :id")
    public suspend fun byId(id: String): ThreadEntity?

    /** 直接子分支的标签；用于计算下一个分叉序号。 */
    @Query("SELECT forkLabel FROM threads WHERE parentThreadId = :parentId")
    public suspend fun childLabels(parentId: String): List<String?>

    @Query(
        "SELECT * FROM threads WHERE archived = 0 " +
            "ORDER BY updatedAt DESC LIMIT :limit OFFSET :offset",
    )
    public suspend fun page(
        offset: Int,
        limit: Int,
    ): List<ThreadEntity>

    /** 对话列表分页：只含来源为 CHAT 的线程（ADR-0031）。 */
    @Query(
        "SELECT * FROM threads WHERE archived = 0 AND origin = 'CHAT' " +
            "ORDER BY updatedAt DESC LIMIT :limit OFFSET :offset",
    )
    public suspend fun pageChat(
        offset: Int,
        limit: Int,
    ): List<ThreadEntity>

    /** 任务运行线程中，创建时间最早、超出 [keep] 个的线程 id。 */
    @Query(
        "SELECT id FROM threads WHERE origin = 'TASK_RUN' " +
            "ORDER BY createdAt DESC LIMIT -1 OFFSET :keep",
    )
    public suspend fun taskRunIdsBeyond(keep: Int): List<String>

    @Query("UPDATE threads SET title = :title WHERE id = :id")
    public suspend fun rename(
        id: String,
        title: String,
    )

    @Query(
        "UPDATE threads SET headSeq = :headSeq, updatedAt = :updatedAt, " +
            "preview = COALESCE(:preview, preview), " +
            "lastRunStatus = COALESCE(:lastRunStatus, lastRunStatus) " +
            "WHERE id = :id",
    )
    public suspend fun updateOnAppend(
        id: String,
        headSeq: Long,
        updatedAt: Long,
        preview: String?,
        lastRunStatus: String?,
    )

    @Query("UPDATE threads SET modelProfileId = :profileId WHERE id = :id")
    public suspend fun setModelProfile(
        id: String,
        profileId: String?,
    )

    @Query("DELETE FROM threads WHERE id = :id")
    public suspend fun delete(id: String)
}

/** 事件行读写。 */
@Dao
public interface EventDao {
    /** 插入并返回自增的全局 seq。 */
    @Insert
    public suspend fun insert(event: ThreadEventEntity): Long

    @Query("UPDATE thread_events SET payload = :payload WHERE seq = :seq")
    public suspend fun setPayload(
        seq: Long,
        payload: String,
    )

    @Query("SELECT * FROM thread_events WHERE seq = :seq")
    public suspend fun bySeq(seq: Long): ThreadEventEntity?

    /** 分支路径上 seq 在 [fromSeq]..[toSeq] 之间的事件，按 seq 升序。 */
    @Query(
        BRANCH_PATH_CTE +
            "SELECT e.* FROM thread_events e JOIN path p ON e.seq = p.seq " +
            "WHERE e.seq >= :fromSeq AND e.seq <= :toSeq ORDER BY e.seq ASC",
    )
    public suspend fun path(
        threadId: String,
        fromSeq: Long,
        toSeq: Long,
    ): List<ThreadEventEntity>

    /** 分支路径上可分叉的事件 seq。 */
    @Query(
        BRANCH_PATH_CTE +
            "SELECT e.seq FROM thread_events e JOIN path p ON e.seq = p.seq " +
            "WHERE e.forkable = 1 ORDER BY e.seq ASC",
    )
    public suspend fun forkableOnPath(threadId: String): List<Long>

    /** 分支路径上最近一条 Baseline 的 seq（类型为 [type]）。 */
    @Query(
        BRANCH_PATH_CTE +
            "SELECT MAX(e.seq) FROM thread_events e JOIN path p ON e.seq = p.seq WHERE e.type = :type",
    )
    public suspend fun lastOnPathOfType(
        threadId: String,
        type: String,
    ): Long?

    /** 不再被任何分支路径引用的事件（待删除）。 */
    @Query(LIVE_EVENTS_CTE + "SELECT * FROM thread_events WHERE seq NOT IN (SELECT seq FROM live)")
    public suspend fun unreachable(): List<ThreadEventEntity>

    @Query("DELETE FROM thread_events WHERE seq IN (:seqs)")
    public suspend fun deleteBySeqs(seqs: List<Long>)

    @Query("SELECT COUNT(*) FROM thread_events WHERE threadId = :threadId")
    public suspend fun countOwned(threadId: String): Int
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

    /** 在分支路径内做 MATCH 查询：[match] 为 bigram 词元表达式（空格连接即 AND）。 */
    @Query(
        BRANCH_PATH_CTE +
            "SELECT threadId, seq, tokens FROM thread_event_fts " +
            "WHERE thread_event_fts MATCH :match AND seq IN (SELECT seq FROM path) " +
            "ORDER BY seq DESC LIMIT :limit",
    )
    public suspend fun match(
        threadId: String,
        match: String,
        limit: Int,
    ): List<FtsRow>

    @Query("DELETE FROM thread_event_fts WHERE threadId = :threadId")
    public suspend fun deleteOwned(threadId: String)

    @Query("DELETE FROM thread_event_fts WHERE seq IN (:seqs)")
    public suspend fun deleteBySeqs(seqs: List<Long>)
}

/** 向量索引读写。 */
@Dao
public interface VectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insert(entity: ThreadEventVectorEntity)

    /** 分支路径内的全部向量。 */
    @Query(
        BRANCH_PATH_CTE +
            "SELECT * FROM thread_event_vectors WHERE seq IN (SELECT seq FROM path)",
    )
    public suspend fun allOnPath(threadId: String): List<ThreadEventVectorEntity>

    /** 归属某分支（写入它的事件）的全部向量：索引重建与清理使用。 */
    @Query("SELECT * FROM thread_event_vectors WHERE threadId = :threadId")
    public suspend fun allOwned(threadId: String): List<ThreadEventVectorEntity>

    @Query("DELETE FROM thread_event_vectors WHERE threadId = :threadId")
    public suspend fun deleteOwned(threadId: String)

    @Query("DELETE FROM thread_event_vectors WHERE seq IN (:seqs)")
    public suspend fun deleteBySeqs(seqs: List<Long>)
}
