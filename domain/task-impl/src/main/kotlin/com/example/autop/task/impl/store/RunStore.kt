/**
 * Run 记录存取（docs/tech/07 §1.2/§3）：[RunRecord] 与 `runs` 表的映射与更新。
 * 更新经互斥串行，避免 watcher 与收尾并发写互相覆盖。
 */
package com.example.autop.task.impl.store

import com.example.autop.agent.PauseReason
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.impl.db.RunEntity
import com.example.autop.task.impl.db.TasksDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Run 记录存储。 */
public class RunStore(
    private val db: TasksDb,
    private val json: Json = AutopJson.default,
) {
    private val mutex = Mutex()
    private val contextSerializer = MapSerializer(String.serializer(), String.serializer())
    private val optionsSerializer = ListSerializer(String.serializer())

    /** 新建或整体替换一条记录。 */
    public suspend fun save(record: RunRecord) {
        mutex.withLock { db.runDao().upsert(record.toEntity()) }
    }

    /**
     * 读-改-写原子更新（N180）：临界区内 get+upsert，串行化各写者的读改写。
     * 仅改部分列的写者必须走此方法——get 后再 update 的两步写不原子，
     * 会被并发写者的旧快照覆盖（如 state 回写清掉 ask_user 的 waitingQuestion）。
     */
    public suspend fun mutate(
        id: RunId,
        transform: (RunRecord) -> RunRecord,
    ) {
        mutex.withLock {
            val record = db.runDao().byId(id.value)?.toRecord()
            if (record != null) {
                db.runDao().upsert(transform(record).toEntity())
            }
        }
    }

    public suspend fun get(id: RunId): RunRecord? = db.runDao().byId(id.value)?.toRecord()

    /** 删除某线程的运行记录（任务运行线程被保留策略清理时使用，ADR-0031）。 */
    public suspend fun deleteByThread(threadId: ThreadId) {
        mutex.withLock { db.runDao().deleteByThread(threadId.value) }
    }

    /** 记录变化流（对话页状态条与问题卡片使用）。 */
    public fun observe(runId: RunId): Flow<RunRecord> =
        db
            .runDao()
            .observe(runId.value)
            .filterNotNull()
            .map { it.toRecord() }

    /** 某线程的运行记录（按开始时间升序）。 */
    public fun observeThread(threadId: ThreadId): Flow<List<RunRecord>> =
        db.runDao().byThread(threadId.value).map { list -> list.map { it.toRecord() } }

    /** 未收口的 Run（AgentHost 常驻条件使用）。 */
    public fun observeUnfinished(): Flow<List<RunRecord>> =
        db.runDao().observeUnfinished().map { list -> list.map { it.toRecord() } }

    /** 进程被杀留下的未收口 Run（WP8.4 启动收口用）。 */
    public suspend fun unfinished(): List<RunRecord> = db.runDao().unfinished().map { it.toRecord() }

    /** 按任务查运行记录（taskId=null 查全部，07 §4.3 运行记录列表）。 */
    public fun observeRuns(
        taskId: com.example.autop.core.common.TaskId?,
        limit: Int,
    ): Flow<List<RunRecord>> = db.runDao().observeRuns(taskId?.value, limit).map { list -> list.map { it.toRecord() } }

    private fun RunRecord.toEntity(): RunEntity =
        RunEntity(
            id = id.value,
            taskId = taskId?.value,
            threadId = threadId.value,
            origin = origin.name,
            status = status.name,
            triggerContextJson = json.encodeToString(contextSerializer, triggerContext),
            createdAt = createdAt,
            startedAt = startedAt,
            endedAt = endedAt,
            summary = summary,
            errorCode = errorCode,
            pauseReason = pauseReason?.name,
            waitingQuestion = waitingQuestion,
            waitingOptionsJson =
                if (waitingOptions.isEmpty()) {
                    null
                } else {
                    json.encodeToString(optionsSerializer, waitingOptions)
                },
        )

    private fun RunEntity.toRecord(): RunRecord =
        RunRecord(
            id = RunId(id),
            taskId =
                taskId?.let {
                    com.example.autop.core.common
                        .TaskId(it)
                },
            threadId = ThreadId(threadId),
            origin = RunOrigin.valueOf(origin),
            status = RunStatus.valueOf(status),
            triggerContext = json.decodeFromString(contextSerializer, triggerContextJson),
            createdAt = createdAt,
            startedAt = startedAt,
            endedAt = endedAt,
            summary = summary,
            errorCode = errorCode,
            pauseReason = pauseReason?.let { PauseReason.valueOf(it) },
            waitingQuestion = waitingQuestion,
            waitingOptions = waitingOptionsJson?.let { json.decodeFromString(optionsSerializer, it) } ?: emptyList(),
        )
}
