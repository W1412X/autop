package com.example.autop.flow.impl.store

import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.FlowId
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.SecretStore
import com.example.autop.flow.ApiEndpoint
import com.example.autop.flow.EndpointStore
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowListItem
import com.example.autop.flow.FlowStateStore
import com.example.autop.flow.FlowStore
import com.example.autop.flow.FlowTree
import com.example.autop.flow.FlowVersion
import com.example.autop.flow.FlowVersionStore
import com.example.autop.flow.StoredFlow
import com.example.autop.flow.impl.db.ApiEndpointEntity
import com.example.autop.flow.impl.db.FlowEntity
import com.example.autop.flow.impl.db.FlowStateEntity
import com.example.autop.flow.impl.db.FlowVersionEntity
import com.example.autop.flow.impl.db.FlowsDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement

/** 流程库（`flows` 表）。 */
internal class RoomFlowStore(
    private val db: FlowsDb,
    private val runs: RoomFlowRunStore,
    private val clock: Clock,
    private val needsScreen: suspend (FlowDefinition) -> Boolean,
) : FlowStore {
    private val json = AutopJson.default

    override fun observeAll(): Flow<List<FlowListItem>> =
        db.flowDao().observeAll().map { list -> list.map { toItem(it) } }

    private suspend fun toItem(e: FlowEntity): FlowListItem {
        val stats = runs.stats(e.id)
        val last = runs.lastRun(e.id)
        return FlowListItem(
            id = FlowId(e.id),
            name = e.name,
            description = e.description,
            nodeCount = e.nodeCount,
            needsScreen = e.needsScreen,
            totalRuns = stats.totalRuns,
            succeededRuns = stats.succeeded,
            lastRunAt = last?.startedAt,
            lastRunStatus = last?.status?.name,
            updatedAt = e.updatedAt,
        )
    }

    override suspend fun get(id: FlowId): StoredFlow? = db.flowDao().byId(id.value)?.let(::decode)

    override suspend fun resolve(nameOrId: String): StoredFlow? {
        val key = nameOrId.trim()
        return (db.flowDao().byId(key) ?: db.flowDao().byName(key))?.let(::decode)
    }

    override suspend fun listAll(): List<StoredFlow> = db.flowDao().all().map(::decode)

    override suspend fun save(definition: FlowDefinition): Outcome<StoredFlow> {
        if (definition.id.isBlank() || definition.name.isBlank()) {
            return Outcome.Err(AppError(code = FlowErrorCodes.INVALID, message = "flow id and name are required"))
        }
        val now = clock.nowMillis()
        val existing = db.flowDao().byId(definition.id)
        val entity =
            FlowEntity(
                id = definition.id,
                name = definition.name,
                description = definition.description,
                json = json.encodeToString(FlowDefinition.serializer(), definition),
                nodeCount = FlowTree.countNodes(definition),
                needsScreen = needsScreen(definition),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        if (existing != null && existing.json != entity.json) {
            // 覆盖保存前保留旧定义（flows-recording-recovery.md A4）。
            val next = (db.flowVersionDao().maxVersion(definition.id) ?: 0) + 1
            db.flowVersionDao().insert(
                FlowVersionEntity(
                    flowId = definition.id,
                    version = next,
                    definitionJson = existing.json,
                    createdAt = now,
                    reason = null,
                ),
            )
        }
        db.flowDao().upsert(entity)
        return Outcome.Ok(decode(entity))
    }

    override suspend fun delete(id: FlowId): Outcome<Unit> {
        db.flowDao().delete(id.value)
        runs.deleteAllOf(id.value)
        db.stateDao().clear(FlowStateStore.flowScope(id.value))
        return Outcome.Ok(Unit)
    }

    override suspend fun exists(id: FlowId): Boolean = db.flowDao().byId(id.value) != null

    private fun decode(e: FlowEntity): StoredFlow =
        StoredFlow(
            id = FlowId(e.id),
            definition = json.decodeFromString(FlowDefinition.serializer(), e.json),
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        )
}

/** 流程历史版本（`flow_versions` 表，docs/design/flows-precondition-selfrepair.md S2）。 */
internal class RoomFlowVersionStore(
    private val db: FlowsDb,
    private val clock: Clock,
) : FlowVersionStore {
    private val json = AutopJson.default

    override suspend fun save(
        flowId: String,
        definition: FlowDefinition,
        reason: String?,
    ) {
        val dao = db.flowVersionDao()
        val encoded = json.encodeToString(FlowDefinition.serializer(), definition)
        // 取号与插入做原子化：主键冲突说明并发占用了同一版本号，重取再插，避免丢版本。
        repeat(VERSION_ATTEMPTS) {
            val next = (dao.maxVersion(flowId) ?: 0) + 1
            val rowId =
                dao.insertIfAbsent(
                    FlowVersionEntity(
                        flowId = flowId,
                        version = next,
                        definitionJson = encoded,
                        createdAt = clock.nowMillis(),
                        reason = reason,
                    ),
                )
            if (rowId != -1L) return
        }
        // 重试耗尽（极端并发）：退回覆盖写入，保证这条版本不丢。
        val next = (dao.maxVersion(flowId) ?: 0) + 1
        dao.insert(
            FlowVersionEntity(
                flowId = flowId,
                version = next,
                definitionJson = encoded,
                createdAt = clock.nowMillis(),
                reason = reason,
            ),
        )
    }

    override suspend fun list(flowId: String): List<FlowVersion> =
        db.flowVersionDao().forFlow(flowId).map { e ->
            FlowVersion(
                flowId = e.flowId,
                version = e.version,
                definition = json.decodeFromString(FlowDefinition.serializer(), e.definitionJson),
                createdAt = e.createdAt,
                reason = e.reason,
            )
        }

    override suspend fun get(
        flowId: String,
        version: Int,
    ): FlowDefinition? =
        db.flowVersionDao().byVersion(flowId, version)?.let {
            json.decodeFromString(FlowDefinition.serializer(), it.definitionJson)
        }

    private companion object {
        const val VERSION_ATTEMPTS: Int = 3
    }
}

/** 跨运行状态（`flow_state` 表）。 */
internal class RoomFlowStateStore(
    private val db: FlowsDb,
    private val clock: Clock,
) : FlowStateStore {
    private val json = AutopJson.default

    override suspend fun get(
        scope: String,
        key: String,
    ): JsonElement? {
        val e = db.stateDao().get(scope, key) ?: return null
        val expires = e.expiresAt
        if (expires != null && expires <= clock.nowMillis()) {
            db.stateDao().delete(scope, key)
            return null
        }
        return json.parseToJsonElement(e.valueJson)
    }

    override suspend fun set(
        scope: String,
        key: String,
        value: JsonElement,
        ttlMs: Long?,
    ) {
        val now = clock.nowMillis()
        db.stateDao().put(
            FlowStateEntity(scope, key, value.toString(), ttlMs?.takeIf { it > 0 }?.let { now + it }, now),
        )
    }

    override suspend fun delete(
        scope: String,
        key: String,
    ) = db.stateDao().delete(scope, key)

    override suspend fun list(scope: String): Map<String, JsonElement> {
        val now = clock.nowMillis()
        return db
            .stateDao()
            .list(scope)
            .filter { it.expiresAt == null || it.expiresAt > now }
            .associate { it.stateKey to json.parseToJsonElement(it.valueJson) }
    }

    override suspend fun clearScope(scope: String) = db.stateDao().clear(scope)
}

/** 接口登记（`api_endpoints` 表；密钥走 [SecretStore]）。 */
internal class RoomEndpointStore(
    private val db: FlowsDb,
    private val secrets: SecretStore,
) : EndpointStore {
    private val json = AutopJson.default
    private val headersSerializer = MapSerializer(String.serializer(), String.serializer())

    override fun observeAll(): Flow<List<ApiEndpoint>> =
        db.endpointDao().observeAll().map { list -> list.map(::decode) }

    override suspend fun get(name: String): ApiEndpoint? = db.endpointDao().byName(name)?.let(::decode)

    override suspend fun save(
        endpoint: ApiEndpoint,
        secret: String?,
    ): Outcome<ApiEndpoint> {
        if (endpoint.name.isBlank() || endpoint.baseUrl.isBlank()) {
            return Outcome.Err(
                AppError(code = FlowErrorCodes.INVALID, message = "endpoint name and baseUrl are required"),
            )
        }
        val ref = endpoint.secretRef ?: secret?.let { "secret:flow:endpoint:${endpoint.name}" }
        if (secret != null && ref != null) secrets.put(ref, secret)
        val saved = endpoint.copy(secretRef = ref)
        db.endpointDao().upsert(
            ApiEndpointEntity(
                name = saved.name,
                baseUrl = saved.baseUrl.trim(),
                headersJson = json.encodeToString(headersSerializer, saved.headers),
                secretRef = saved.secretRef,
                secretHeader = saved.secretHeader,
                secretPrefix = saved.secretPrefix,
                enabled = saved.enabled,
            ),
        )
        return Outcome.Ok(saved)
    }

    override suspend fun delete(name: String) {
        db
            .endpointDao()
            .byName(name)
            ?.secretRef
            ?.let { secrets.remove(it) }
        db.endpointDao().delete(name)
    }

    private fun decode(e: ApiEndpointEntity): ApiEndpoint =
        ApiEndpoint(
            name = e.name,
            baseUrl = e.baseUrl,
            headers = json.decodeFromString(headersSerializer, e.headersJson),
            secretRef = e.secretRef,
            secretHeader = e.secretHeader,
            secretPrefix = e.secretPrefix,
            enabled = e.enabled,
        )
}
