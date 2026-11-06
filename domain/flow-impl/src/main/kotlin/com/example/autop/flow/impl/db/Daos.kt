@file:Suppress("TooManyFunctions") // DAO 的查询面

package com.example.autop.flow.impl.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 流程域数据库（版本 1，各表一次建全）。 */
@Database(
    entities = [
        FlowEntity::class,
        FlowDraftEntity::class,
        FlowRunEntity::class,
        FlowNodeRunEntity::class,
        FlowStatsEntity::class,
        FlowNodeStatsEntity::class,
        FlowStateEntity::class,
        ApiEndpointEntity::class,
        FlowVersionEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
internal abstract class FlowsDb : RoomDatabase() {
    abstract fun flowDao(): FlowDao

    abstract fun flowVersionDao(): FlowVersionDao

    abstract fun runDao(): FlowRunDao

    abstract fun statsDao(): FlowStatsDao

    abstract fun stateDao(): FlowStateDao

    abstract fun endpointDao(): EndpointDao
}

@Dao
internal interface FlowDao {
    @Query("SELECT * FROM flows ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<FlowEntity>>

    @Query("SELECT * FROM flows")
    suspend fun all(): List<FlowEntity>

    @Query("SELECT * FROM flows WHERE id = :id")
    suspend fun byId(id: String): FlowEntity?

    @Query("SELECT * FROM flows WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): FlowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FlowEntity)

    @Query("DELETE FROM flows WHERE id = :id")
    suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDraft(entity: FlowDraftEntity)

    @Query("SELECT * FROM flow_drafts WHERE draftId = :id")
    suspend fun draft(id: String): FlowDraftEntity?

    @Query("SELECT * FROM flow_drafts ORDER BY updatedAt DESC")
    suspend fun drafts(): List<FlowDraftEntity>

    @Query("DELETE FROM flow_drafts WHERE draftId = :id")
    suspend fun deleteDraft(id: String)
}

@Dao
internal interface FlowRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FlowRunEntity)

    @Query("SELECT * FROM flow_runs WHERE runId = :runId")
    suspend fun byId(runId: String): FlowRunEntity?

    @Query("SELECT * FROM flow_runs WHERE runId = :runId")
    fun observeById(runId: String): Flow<FlowRunEntity?>

    @Query("SELECT * FROM flow_runs WHERE (:flowId IS NULL OR flowId = :flowId) ORDER BY startedAt DESC")
    fun observe(flowId: String?): Flow<List<FlowRunEntity>>

    @Query("SELECT COALESCE(SUM(llmTokens), 0) FROM flow_runs WHERE startedAt >= :since")
    suspend fun tokensSince(since: Long): Long

    @Query("SELECT * FROM flow_runs WHERE flowId = :flowId ORDER BY startedAt DESC LIMIT 1")
    suspend fun last(flowId: String): FlowRunEntity?

    @Query("SELECT * FROM flow_runs WHERE status = 'running' OR status = 'paused'")
    suspend fun unfinished(): List<FlowRunEntity>

    @Query("DELETE FROM flow_runs WHERE runId = :runId")
    suspend fun delete(runId: String)

    @Query("DELETE FROM flow_runs WHERE flowId = :flowId")
    suspend fun deleteByFlow(flowId: String)

    @Insert
    suspend fun insertNode(entity: FlowNodeRunEntity): Long

    @Update
    suspend fun updateNode(entity: FlowNodeRunEntity)

    @Query("SELECT * FROM flow_node_runs WHERE rowId = :rowId")
    suspend fun nodeById(rowId: Long): FlowNodeRunEntity?

    @Query("SELECT * FROM flow_node_runs WHERE runId = :runId ORDER BY seq")
    suspend fun nodes(runId: String): List<FlowNodeRunEntity>

    @Query("SELECT * FROM flow_node_runs WHERE runId = :runId ORDER BY seq")
    fun observeNodes(runId: String): Flow<List<FlowNodeRunEntity>>

    @Query("SELECT COUNT(*) FROM flow_node_runs WHERE runId = :runId")
    suspend fun nodeCount(runId: String): Int

    @Query("DELETE FROM flow_node_runs WHERE runId = :runId")
    suspend fun deleteNodes(runId: String)

    @Query("DELETE FROM flow_node_runs WHERE flowId = :flowId")
    suspend fun deleteNodesByFlow(flowId: String)
}

@Dao
internal interface FlowStatsDao {
    @Query("SELECT * FROM flow_stats WHERE flowId = :flowId")
    suspend fun stats(flowId: String): FlowStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStats(entity: FlowStatsEntity)

    @Query("SELECT * FROM flow_node_stats WHERE flowId = :flowId")
    suspend fun nodeStats(flowId: String): List<FlowNodeStatsEntity>

    @Query("SELECT * FROM flow_node_stats WHERE flowId = :flowId AND nodeId = :nodeId")
    suspend fun nodeStat(
        flowId: String,
        nodeId: String,
    ): FlowNodeStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNodeStats(entity: FlowNodeStatsEntity)

    @Query("DELETE FROM flow_stats WHERE flowId = :flowId")
    suspend fun deleteStats(flowId: String)

    @Query("DELETE FROM flow_node_stats WHERE flowId = :flowId")
    suspend fun deleteNodeStats(flowId: String)
}

@Dao
internal interface FlowStateDao {
    @Query("SELECT * FROM flow_state WHERE scope = :scope AND stateKey = :key")
    suspend fun get(
        scope: String,
        key: String,
    ): FlowStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: FlowStateEntity)

    @Query("DELETE FROM flow_state WHERE scope = :scope AND stateKey = :key")
    suspend fun delete(
        scope: String,
        key: String,
    )

    @Query("SELECT * FROM flow_state WHERE scope = :scope")
    suspend fun list(scope: String): List<FlowStateEntity>

    @Query("DELETE FROM flow_state WHERE scope = :scope")
    suspend fun clear(scope: String)
}

@Dao
internal interface EndpointDao {
    @Query("SELECT * FROM api_endpoints ORDER BY name")
    fun observeAll(): Flow<List<ApiEndpointEntity>>

    @Query("SELECT * FROM api_endpoints WHERE name = :name")
    suspend fun byName(name: String): ApiEndpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ApiEndpointEntity)

    @Query("DELETE FROM api_endpoints WHERE name = :name")
    suspend fun delete(name: String)
}

/** 流程定义的历史版本（覆盖保存时记录旧定义）。 */
@Dao
internal interface FlowVersionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FlowVersionEntity)

    /** 冲突（同一 flowId + version 已存在）时不写入，返回 -1；调用方据此重取版本号。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: FlowVersionEntity): Long

    @Query("SELECT MAX(version) FROM flow_versions WHERE flowId = :flowId")
    suspend fun maxVersion(flowId: String): Int?

    @Query("SELECT * FROM flow_versions WHERE flowId = :flowId ORDER BY version DESC")
    suspend fun forFlow(flowId: String): List<FlowVersionEntity>

    @Query("SELECT * FROM flow_versions WHERE flowId = :flowId AND version = :version")
    suspend fun byVersion(
        flowId: String,
        version: Int,
    ): FlowVersionEntity?
}
