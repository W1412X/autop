@file:Suppress("LongParameterList") // 会话依赖持有者

package com.example.autop.flow.impl.run

import com.example.autop.agent.AgentSession
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunLineage
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunRequest
import com.example.autop.agent.RunStarted
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolInvoker
import com.example.autop.agent.ToolInvokerFactory
import com.example.autop.agent.ToolProbeService
import com.example.autop.agent.UserInteraction
import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.SecretStore
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.FlowConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.DeviceStateProvider
import com.example.autop.device.NotificationFeed
import com.example.autop.flow.EndpointStore
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowRunHandle
import com.example.autop.flow.FlowRunRequest
import com.example.autop.flow.FlowRunStatus
import com.example.autop.flow.FlowSession
import com.example.autop.flow.FlowStateStore
import com.example.autop.flow.FlowStore
import com.example.autop.flow.core.FlowClock
import com.example.autop.flow.core.FlowInterpreter
import com.example.autop.flow.core.FlowPorts
import com.example.autop.flow.core.FlowRunInput
import com.example.autop.flow.core.FlowRunResult
import com.example.autop.flow.impl.db.FlowRunEntity
import com.example.autop.flow.impl.ports.AgentPortImpl
import com.example.autop.flow.impl.ports.ApiPortImpl
import com.example.autop.flow.impl.ports.DevicePortImpl
import com.example.autop.flow.impl.ports.FlowImages
import com.example.autop.flow.impl.ports.FlowResolverImpl
import com.example.autop.flow.impl.ports.FlowToolContext
import com.example.autop.flow.impl.ports.LlmPortImpl
import com.example.autop.flow.impl.ports.NotificationPortImpl
import com.example.autop.flow.impl.ports.ObservePortImpl
import com.example.autop.flow.impl.ports.StatePortImpl
import com.example.autop.flow.impl.ports.ToolPortImpl
import com.example.autop.flow.impl.ports.UserPortImpl
import com.example.autop.flow.impl.store.RoomFlowRunStore
import com.example.autop.flow.impl.store.RunEnd
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryStore
import com.example.autop.memory.MemoryWrite
import com.example.autop.memory.WriteMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** 流程会话依赖（DI 装配）。 */
internal class FlowSessionDeps(
    /** 以下三项用 Provider 延迟取得：工具集合依赖 run_flow，直接注入会形成依赖环。 */
    val agentSession: () -> AgentSession,
    val threadStore: ThreadStore,
    val invokerFactory: () -> ToolInvokerFactory,
    val probe: () -> ToolProbeService,
    val interaction: UserInteraction,
    val lineage: RunLineage,
    val gateway: com.example.autop.llm.LlmGateway,
    val prompts: PromptRepository,
    val configProvider: ConfigProvider,
    val clock: Clock,
    val ids: IdGenerator,
    val logger: Logger,
    val dispatchers: DispatcherProvider,
    val secrets: SecretStore,
    val endpoints: EndpointStore,
    val flows: FlowStore,
    val state: FlowStateStore,
    val runs: RoomFlowRunStore,
    val feed: NotificationFeed,
    val device: DeviceStateProvider,
    val http: OkHttpClient,
    val memory: MemoryStore,
)

/** 流程会话实现：装配端口、执行解释器、写执行记录并收口。 */
internal class FlowSessionImpl(
    private val d: FlowSessionDeps,
) : FlowSession {
    private val json = AutopJson.default

    /** 正在运行的流程实例（按流程 id）：修订从这里推给对应实例。 */
    private val running = ConcurrentHashMap<String, FlowRunHandleImpl>()

    @Suppress("TooGenericExceptionCaught") // 解释器外的装配异常统一转 FAILED，不外传
    override fun start(
        request: FlowRunRequest,
        scope: CoroutineScope,
    ): FlowRunHandle {
        val handle =
            FlowRunHandleImpl(
                runId = request.runId,
                threadId = request.threadId,
                store = d.threadStore,
                scope = scope,
                eventReplay = request.config[AgentConfig.UI].eventReplay,
                debug = request.options.debug,
                initialBreakpoints = request.options.breakpoints,
            )
        val totals = RunTotals()
        // 解释器在协程外装配：修订入口要在运行开始前就挂上，避免确认草稿时推不到。
        val interpreter = buildInterpreter(request, scope, handle, totals)
        handle.attachPatcher { definition -> interpreter.applyPatch("", definition) }
        running[request.flow.id] = handle
        val job =
            scope.launch {
                var result: FlowRunResult? = null
                try {
                    result = execute(request, handle, interpreter)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { finish(request, handle, totals, cancelled()) }
                    throw e
                } catch (e: Exception) {
                    d.logger.e(TAG, "flow_run_crashed", "runId" to request.runId.value, error = e)
                    result = FlowRunResult(RunOutcome.FAILED, CommonErrorCodes.UNKNOWN, e.message, null, 0)
                }
                finish(request, handle, totals, result)
                running.remove(request.flow.id, handle)
            }
        handle.attach(job)
        return handle
    }

    /**
     * 把修订推给正在运行的该流程实例（docs/design/flows-precondition-selfrepair.md S3）。
     * 没有运行中的实例时返回失败；结构不合法时由解释器判定。
     */
    override suspend fun applyPatch(
        flowId: String,
        definition: FlowDefinition,
    ): Outcome<Unit> {
        val handle =
            running[flowId]
                ?: return Outcome.Err(AppError(FlowErrorCodes.NOT_FOUND, "流程 $flowId 没有正在运行的实例"))
        return handle.applyPatch(definition)
    }

    private fun buildInterpreter(
        request: FlowRunRequest,
        scope: CoroutineScope,
        handle: FlowRunHandleImpl,
        totals: RunTotals,
    ): FlowInterpreter {
        val ports = buildPorts(request, scope, handle, totals)
        val flowCfg = request.config
        return FlowInterpreter(
            ports,
            flowCfg[FlowConfig.RUN],
            flowCfg[FlowConfig.LLM],
            flowCfg[FlowConfig.AGENT],
            flowCfg[FlowConfig.RECOVERY],
            flowCfg[FlowConfig.PRECONDITION],
            flowCfg[FlowConfig.CONTRACT],
        )
    }

    private fun cancelled() = FlowRunResult(RunOutcome.CANCELLED, null, null, null, 0)

    /** 流程侧工具上下文：来源标签带流程名（memory_write 的默认 reason 用）。 */
    private fun flowToolContext(
        request: FlowRunRequest,
        invoker: ToolInvoker,
    ): FlowToolContext =
        FlowToolContext(
            runId = request.runId,
            threadId = request.threadId,
            config = request.config,
            threadStore = d.threadStore,
            interaction = d.interaction,
            invoker = invoker,
            tag = "flow:${request.flow.name}",
        ) { }

    private suspend fun execute(
        request: FlowRunRequest,
        handle: FlowRunHandleImpl,
        interpreter: FlowInterpreter,
    ): FlowRunResult {
        val flow = request.flow
        handle.append(
            RunStarted(
                runId = request.runId,
                configDiff = JsonObject(emptyMap()),
                promptVersions = emptyMap(),
                modelProfileId = FLOW_PROFILE,
                toolIds = emptyList(),
            ),
        )
        d.runs.begin(runEntity(request, flow))
        val result =
            interpreter.run(
                FlowRunInput(
                    runId = request.runId.value,
                    flow = flow,
                    params = request.params,
                    trigger = request.trigger,
                    taskId = request.taskId?.value,
                    origin = request.origin,
                    options = request.options,
                ),
            )
        if (flow.settings.memoryLog.enabled) logToMemory(request, result)
        return result
    }

    /** 运行摘要写入记忆（settings.memoryLog，默认关）：每个流程一个条目，只保留最近 keep 行。 */
    private suspend fun logToMemory(
        request: FlowRunRequest,
        result: FlowRunResult,
    ) {
        val flow = request.flow
        val path = (MemoryPath.parse("/tasks/flow-${flow.id}") as? Outcome.Ok)?.value ?: return
        val node = d.memory.get(path)
        val existing =
            node
                ?.content
                .orEmpty()
                .lines()
                .filter { it.isNotBlank() }
        val stamp =
            java.time.Instant
                .ofEpochMilli(d.clock.nowMillis())
                .atZone(java.time.ZoneId.systemDefault())
        val line = "${stamp.toLocalDateTime().withNano(
            0,
        )} ${result.status.name.lowercase()} ${summaryOf(flow, result).orEmpty()}"
        val lines =
            (existing + line).takeLast(
                flow.settings.memoryLog.keep
                    .coerceAtLeast(1),
            )
        d.memory.write(
            MemoryWrite(
                path = path,
                mode = if (node == null) WriteMode.CREATE else WriteMode.UPDATE,
                title = flow.name,
                content = lines.joinToString("\n"),
                source = MemorySource.FLOW,
                runId = request.runId,
                reason = "flow:${flow.name}",
            ),
        )
    }

    private fun buildPorts(
        request: FlowRunRequest,
        scope: CoroutineScope,
        handle: FlowRunHandleImpl,
        totals: RunTotals,
    ): FlowPorts {
        val flow = request.flow
        val filter = request.toolFilter ?: flow.settings.allowedTools?.toSet()
        val invoker =
            d.invokerFactory().create(
                RunRequest(
                    runId = request.runId,
                    threadId = request.threadId,
                    input = emptyList(),
                    goal = null,
                    vars = emptyMap(),
                    config = request.config,
                    toolFilter = filter,
                ),
                filter,
            )
        val ctx = flowToolContext(request, invoker)
        val images = FlowImages()
        val access = d.probe().targets().associate { it.name to it.access }
        val observe = ObservePortImpl(invoker, ctx, images, flow.settings.observeMode)
        val trace =
            TracePortImpl(
                request.runId.value,
                d.runs,
                d.clock,
                request.config[FlowConfig.TRACE].maxDetailRowsPerRun,
                totals,
            )
        return FlowPorts(
            tools = ToolPortImpl(invoker, ctx, d.threadStore, d.ids, d.clock, access),
            observe = observe,
            llm = LlmPortImpl(d.gateway, d.prompts, images, observe, d.configProvider, { usedTokensToday() }, d.clock),
            api = ApiPortImpl(d.endpoints, d.secrets, d.configProvider, d.dispatchers, d.http),
            agent =
                AgentPortImpl(
                    d.agentSession(),
                    d.threadStore,
                    d.lineage,
                    d.ids,
                    d.configProvider,
                    request.config,
                    request.runId,
                    scope,
                    d.prompts,
                ),
            state = StatePortImpl(d.state),
            notifications = NotificationPortImpl(d.feed),
            device = DevicePortImpl(d.device),
            user = UserPortImpl(d.interaction, d.prompts, request.runId),
            flows = FlowResolverImpl(d.flows),
            trace = trace,
            control = handle,
            clock = SystemFlowClock(d.clock),
        )
    }

    private suspend fun usedTokensToday(): Long {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(d.clock.nowMillis()).atZone(zone).toLocalDate()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        return d.runs.tokensSince(start)
    }

    private suspend fun finish(
        request: FlowRunRequest,
        handle: FlowRunHandleImpl,
        totals: RunTotals,
        result: FlowRunResult,
    ) {
        val status = flowStatus(result.status)
        val summary = summaryOf(request.flow, result)
        d.runs.finish(
            request.runId.value,
            RunEnd(
                status = status,
                endedAt = d.clock.nowMillis(),
                errorCode = result.errorCode,
                summary = summary,
                outputs = result.outputs,
                llmCalls = totals.llmCalls.get(),
                llmTokens = totals.llmTokens.get(),
                apiCalls = totals.apiCalls.get(),
                handoffs = totals.handoffs.get(),
            ),
        )
        val finished =
            RunFinished(
                status = result.status,
                summary = summary,
                outputs = result.outputs,
                errorCode = result.errorCode,
                failedNodeId = result.failedNodeId,
                failedNodeName = result.failedNodeName,
            )
        handle.append(finished)
        handle.complete(finished)
    }

    private fun flowStatus(outcome: RunOutcome): FlowRunStatus =
        when (outcome) {
            RunOutcome.SUCCEEDED -> FlowRunStatus.SUCCEEDED
            RunOutcome.CANCELLED -> FlowRunStatus.CANCELLED
            else -> FlowRunStatus.FAILED
        }

    private fun summaryOf(
        flow: FlowDefinition,
        result: FlowRunResult,
    ): String? {
        result.message?.takeIf { it.isNotBlank() }?.let { return it }
        val id =
            when (result.status) {
                RunOutcome.SUCCEEDED -> SUMMARY_OK
                RunOutcome.CANCELLED -> SUMMARY_CANCELLED
                else -> SUMMARY_FAILED
            }
        val vars =
            mapOf(
                "name" to flow.name,
                "count" to result.nodesExecuted.toString(),
                "error" to result.errorCode.orEmpty(),
            )
        return (d.prompts.render(id, vars) as? Outcome.Ok)?.value?.text
    }

    private fun runEntity(
        request: FlowRunRequest,
        flow: FlowDefinition,
    ): FlowRunEntity {
        val secretNames =
            flow.params
                .filter { it.secret }
                .map { it.name }
                .toSet()
        val params: Map<String, JsonElement> =
            request.params.mapValues { (k, v) -> if (k in secretNames) JsonPrimitive(REDACTED) else v }
        return FlowRunEntity(
            runId = request.runId.value,
            flowId = flow.id,
            flowName = flow.name,
            taskId = request.taskId?.value,
            origin = request.origin,
            paramsJson = JsonObject(params).toString(),
            status = "running",
            startedAt = d.clock.nowMillis(),
            endedAt = null,
            durationMs = null,
            errorCode = null,
            summary = null,
            llmCalls = 0,
            llmTokens = 0,
            apiCalls = 0,
            handoffs = 0,
            outputsJson = null,
            snapshotJson = json.encodeToString(FlowDefinition.serializer(), flow),
        )
    }

    private companion object {
        const val TAG = "FlowSession"
        const val FLOW_PROFILE = "flow"
        const val REDACTED = "***"
        const val SUMMARY_OK = "flow.summary.succeeded"
        const val SUMMARY_FAILED = "flow.summary.failed"
        const val SUMMARY_CANCELLED = "flow.summary.cancelled"
    }
}

/** 系统时间/随机源。 */
internal class SystemFlowClock(
    private val clock: Clock,
) : FlowClock {
    override fun nowMs(): Long = clock.nowMillis()

    override val zone: ZoneId = ZoneId.systemDefault()

    override fun random(
        lo: Double,
        hi: Double,
    ): Double = if (hi <= lo) lo else lo + Random.nextDouble() * (hi - lo)
}
