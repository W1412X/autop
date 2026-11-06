package com.example.autop.task.impl

import com.example.autop.core.common.FlowId
import com.example.autop.core.common.Outcome
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowListItem
import com.example.autop.flow.FlowRunHandle
import com.example.autop.flow.FlowRunRequest
import com.example.autop.flow.FlowSession
import com.example.autop.flow.FlowStore
import com.example.autop.flow.StoredFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 测试用：不启动任何流程。 */
internal object FakeFlowSession : FlowSession {
    override fun start(
        request: FlowRunRequest,
        scope: CoroutineScope,
    ): FlowRunHandle = error("flows are not used in this test")
}

/** 测试用：空流程库。 */
internal object FakeFlowStore : FlowStore {
    override fun observeAll(): Flow<List<FlowListItem>> = flowOf(emptyList())

    override suspend fun get(id: FlowId): StoredFlow? = null

    override suspend fun resolve(nameOrId: String): StoredFlow? = null

    override suspend fun listAll(): List<StoredFlow> = emptyList()

    override suspend fun save(definition: FlowDefinition): Outcome<StoredFlow> = error("unused")

    override suspend fun delete(id: FlowId): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun exists(id: FlowId): Boolean = false
}
