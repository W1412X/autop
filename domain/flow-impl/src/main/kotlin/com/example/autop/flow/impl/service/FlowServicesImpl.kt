@file:Suppress("LoopWithTooManyJumpStatements") // 导入循环按冲突策略跳过

package com.example.autop.flow.impl.service

import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolProbeService
import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.FlowId
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.flow.ApiNode
import com.example.autop.flow.BundleEndpoint
import com.example.autop.flow.EndpointStore
import com.example.autop.flow.EndpointTestResult
import com.example.autop.flow.EndpointTester
import com.example.autop.flow.FlowBundle
import com.example.autop.flow.FlowBundleService
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowIssue
import com.example.autop.flow.FlowSpec
import com.example.autop.flow.FlowStore
import com.example.autop.flow.FlowTree
import com.example.autop.flow.FlowValidationService
import com.example.autop.flow.ImportConflict
import com.example.autop.flow.ImportReport
import com.example.autop.flow.IssueSeverity
import com.example.autop.flow.core.ApiCall
import com.example.autop.flow.core.ApiPort
import com.example.autop.flow.core.FlowAnalyzer
import com.example.autop.flow.core.FlowCatalog
import com.example.autop.flow.core.FlowValidator
import com.example.autop.flow.core.expr.FlowException
import kotlinx.coroutines.flow.first

/** 为校验器预加载一份流程/接口/工具快照。 */
internal class FlowCatalogBuilder(
    private val storeOf: () -> FlowStore,
    private val endpointsOf: () -> EndpointStore,
    private val probeOf: () -> ToolProbeService,
) {
    suspend fun build(extra: FlowDefinition? = null): FlowCatalog {
        val flows = storeOf().listAll().map { it.definition }.filter { it.id != extra?.id } + listOfNotNull(extra)
        val registered =
            endpointsOf()
                .observeAll()
                .first()
                .map { it.name }
                .toSet()
        val access = probeOf().targets().associate { it.name to it.access }
        return object : FlowCatalog {
            override fun toolAccess(name: String): ToolAccess? = access[name]

            override fun flow(nameOrId: String): FlowDefinition? =
                flows.firstOrNull { it.id == nameOrId }
                    ?: flows.firstOrNull { it.name.equals(nameOrId, ignoreCase = true) }

            override fun endpointRegistered(name: String): Boolean = name in registered
        }
    }
}

/** 校验与 needsScreen 分析。 */
internal class FlowValidationServiceImpl(
    private val catalogs: FlowCatalogBuilder,
) : FlowValidationService {
    override suspend fun validate(definition: FlowDefinition): List<FlowIssue> =
        FlowValidator(catalogs.build(definition)).validate(definition)

    override suspend fun needsScreen(definition: FlowDefinition): Boolean {
        val catalog = catalogs.build(definition)
        return FlowAnalyzer.needsScreen(definition, catalog) { tool ->
            val access = catalog.toolAccess(tool)
            access == ToolAccess.READ_SCREEN || access == ToolAccess.WRITE_SCREEN
        }
    }
}

/** 导入导出：JSON 包，不含密钥。 */
internal class FlowBundleServiceImpl(
    private val store: FlowStore,
    private val endpoints: EndpointStore,
    private val validation: FlowValidationService,
    private val ids: IdGenerator,
) : FlowBundleService {
    private val json = AutopJson.default

    override suspend fun export(flowIds: List<String>): Outcome<String> {
        val flows = flowIds.mapNotNull { store.get(FlowId(it))?.definition }
        if (flows.isEmpty()) {
            return Outcome.Err(
                AppError(code = FlowErrorCodes.NOT_FOUND, message = "no flows to export"),
            )
        }
        val used = flows.flatMap { f -> FlowTree.allNodes(f).filterIsInstance<ApiNode>().map { it.endpoint } }.toSet()
        val bundleEndpoints =
            used.mapNotNull { endpoints.get(it) }.map { BundleEndpoint(it.name, it.baseUrl, it.headers) }
        return Outcome.Ok(
            json.encodeToString(FlowBundle.serializer(), FlowBundle(flows = flows, endpoints = bundleEndpoints)),
        )
    }

    override fun parse(text: String): Outcome<FlowBundle> =
        try {
            val bundle = json.decodeFromString(FlowBundle.serializer(), text)
            if (bundle.format != FlowSpec.BUNDLE_FORMAT) {
                Outcome.Err(AppError(code = FlowErrorCodes.INVALID, message = "unexpected format ${bundle.format}"))
            } else if (bundle.version > FlowSpec.BUNDLE_VERSION) {
                Outcome.Err(
                    AppError(code = FlowErrorCodes.INVALID, message = "bundle version ${bundle.version} is newer"),
                )
            } else {
                Outcome.Ok(bundle)
            }
        } catch (e: IllegalArgumentException) {
            Outcome.Err(AppError(code = FlowErrorCodes.INVALID, message = e.message ?: "invalid bundle"))
        }

    override suspend fun import(
        bundle: FlowBundle,
        onConflict: ImportConflict,
    ): ImportReport {
        val imported = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val issues = mutableListOf<FlowIssue>()
        for (flow in bundle.flows) {
            val target = resolveConflict(flow, onConflict)
            if (target == null) {
                skipped += flow.name
                continue
            }
            val found = validation.validate(target)
            issues += found
            if (found.any { it.severity == IssueSeverity.ERROR }) {
                skipped += flow.name
                continue
            }
            if (store.save(target) is Outcome.Ok) imported += target.name else skipped += flow.name
        }
        val registered =
            endpoints
                .observeAll()
                .first()
                .map { it.name }
                .toSet()
        val missing = bundle.endpoints.filter { it.name !in registered }
        return ImportReport(imported, skipped, missing, issues)
    }

    private suspend fun resolveConflict(
        flow: FlowDefinition,
        policy: ImportConflict,
    ): FlowDefinition? {
        val clash = store.exists(FlowId(flow.id)) || store.resolve(flow.name) != null
        if (!clash) return flow
        return when (policy) {
            ImportConflict.REPLACE ->
                (store.resolve(flow.id) ?: store.resolve(flow.name))?.let { flow.copy(id = it.definition.id) }
                    ?: flow
            ImportConflict.KEEP_BOTH -> flow.copy(id = "flow_" + ids.newId(), name = flow.name + SUFFIX)
            ImportConflict.SKIP -> null
        }
    }

    private companion object {
        const val SUFFIX = " (2)"
    }
}

/** 接口测试：对已登记接口发一次 GET。 */
internal class EndpointTesterImpl(
    private val api: ApiPort,
    private val clock: Clock,
) : EndpointTester {
    override suspend fun test(
        name: String,
        path: String,
    ): EndpointTestResult {
        val started = clock.nowMillis()
        return try {
            val reply =
                api.call(
                    ApiCall(name, "GET", path, emptyMap(), emptyMap(), "none", null, null),
                )
            EndpointTestResult(reply.status in OK_RANGE, reply.status, clock.nowMillis() - started, null)
        } catch (e: FlowException) {
            EndpointTestResult(false, null, clock.nowMillis() - started, e.message)
        }
    }

    private companion object {
        val OK_RANGE = 200..299
    }
}
