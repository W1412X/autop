/**
 * [FlowVersionsViewModel] 单测用的 FlowStore / FlowVersionStore 假实现。
 */
package com.example.autop.feature.flows.versions

import com.example.autop.core.common.AppError
import com.example.autop.core.common.FlowId
import com.example.autop.core.common.Outcome
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowListItem
import com.example.autop.flow.FlowStore
import com.example.autop.flow.FlowVersion
import com.example.autop.flow.FlowVersionStore
import com.example.autop.flow.StoredFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map

/** 假流程库：可注入当前定义并手动触发变更信号。 */
internal class FakeFlowStore(
    var current: StoredFlow? = null,
) : FlowStore {
    private val changes = MutableSharedFlow<Unit>(replay = 1)

    /** 触发一次变更信号（模拟保存/回滚写入流程定义）。 */
    fun emitChange() {
        changes.tryEmit(Unit)
    }

    override fun observeAll(): Flow<List<FlowListItem>> =
        changes.map {
            current
                ?.let {
                    listOf(
                        FlowListItem(
                            id = it.id,
                            name = it.definition.name,
                            description = it.definition.description,
                            nodeCount = 0,
                            needsScreen = false,
                            totalRuns = 0,
                            succeededRuns = 0,
                            lastRunAt = null,
                            lastRunStatus = null,
                            updatedAt = it.updatedAt,
                        ),
                    )
                }.orEmpty()
        }

    override suspend fun get(id: FlowId): StoredFlow? = current?.takeIf { it.id == id }

    override suspend fun resolve(nameOrId: String): StoredFlow? = current

    override suspend fun listAll(): List<StoredFlow> = listOfNotNull(current)

    override suspend fun save(definition: FlowDefinition): Outcome<StoredFlow> {
        val stored = StoredFlow(FlowId(definition.id), definition, 0L, 0L)
        current = stored
        emitChange()
        return Outcome.Ok(stored)
    }

    override suspend fun delete(id: FlowId): Outcome<Unit> {
        current = null
        emitChange()
        return Outcome.Ok(Unit)
    }

    override suspend fun exists(id: FlowId): Boolean = current != null
}

/** 假版本库：内存列表，restore 会写回主定义并追加版本（与 Room 实现语义一致）。 */
internal class FakeFlowVersionStore(
    private val flows: FakeFlowStore,
) : FlowVersionStore {
    val versions = mutableListOf<FlowVersion>()
    var restoreCount = 0

    override suspend fun save(
        flowId: String,
        definition: FlowDefinition,
        reason: String?,
    ) {
        versions +=
            FlowVersion(
                flowId = flowId,
                version = (versions.maxOfOrNull { it.version } ?: 0) + 1,
                definition = definition,
                createdAt = 0L,
                reason = reason,
            )
    }

    override suspend fun list(flowId: String): List<FlowVersion> = versions.filter { it.flowId == flowId }.reversed()

    override suspend fun get(
        flowId: String,
        version: Int,
    ): FlowDefinition? = versions.firstOrNull { it.flowId == flowId && it.version == version }?.definition

    override suspend fun restore(
        flowId: String,
        version: Int,
    ): Outcome<StoredFlow> {
        restoreCount++
        val definition =
            get(flowId, version)
                ?: return Outcome.Err(AppError(code = "not_found", message = "no version $version"))
        // 回滚语义：当前定义进版本库，恢复目标定义为当前。
        flows.current?.let { save(flowId, it.definition, null) }
        return flows.save(definition)
    }
}
