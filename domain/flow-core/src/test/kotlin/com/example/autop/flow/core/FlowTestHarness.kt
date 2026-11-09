/**
 * 流程解释器的测试夹具（docs/design/flows-precondition-selfrepair.md P8/C5/S5 共用）。
 *
 * 假端口：屏幕内容可改、工具调用可计数、智能体交接可捕获、时钟可推进。
 */
package com.example.autop.flow.core

import com.example.autop.agent.PauseReason
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolControl
import com.example.autop.core.config.FlowAgentSection
import com.example.autop.core.config.FlowContractSection
import com.example.autop.core.config.FlowLlmSection
import com.example.autop.core.config.FlowPreconditionSection
import com.example.autop.core.config.FlowRecoverySection
import com.example.autop.core.config.FlowRunSection
import com.example.autop.device.DeviceState
import com.example.autop.device.NotificationInfo
import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenNode
import com.example.autop.device.ScreenQuality
import com.example.autop.device.ScreenWindow
import com.example.autop.flow.Condition
import com.example.autop.flow.Expect
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowSettings
import com.example.autop.flow.Node
import com.example.autop.flow.OnFail
import com.example.autop.flow.PauseResolution
import com.example.autop.flow.Precondition
import com.example.autop.flow.ToolNode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId

/** 解释器测试夹具。 */
internal class FlowTestHarness {
    /** 工具调用记录（工具名 + 参数文本）。 */
    val toolCalls = mutableListOf<String>()

    /** 剩余的工具失败次数。 */
    var toolFailuresLeft: Int = 0

    /** 工具失败时的错误码。 */
    var toolErrorCode: String = "tool.fail"

    /** 屏幕上的文字（条件求值与页面摘要都用它）。 */
    var screenText: String = "发布页"

    /** 前台应用包名。 */
    var foregroundPackage: String = "com.demo"

    /** 观测次数（断言"重新求值了几次"）。 */
    var observeCount: Int = 0

    /** 智能体收到的目标文本。 */
    val agentGoals = mutableListOf<String>()

    /** 智能体收到的流程上下文。 */
    val agentContexts = mutableListOf<AgentContext>()

    /** 智能体是否成功。 */
    var agentOk: Boolean = true

    /** 智能体执行时的副作用（模拟"把页面带到正确状态"）。 */
    var onAgentRun: (() -> Unit)? = null

    /** 解释器日志。 */
    val logs = mutableListOf<String>()

    /** 假时钟：每次读时间前进 [tickMs] 毫秒（等待策略需要时间推进）。 */
    var clockMillis: Long = 0L
    var tickMs: Long = 100L

    /** 假时钟（可单独取用，构造 Frame 时需要）。 */
    val clock: FlowClock =
        object : FlowClock {
            override fun nowMs(): Long {
                val now = clockMillis
                clockMillis += tickMs
                return now
            }

            override val zone: ZoneId = ZoneId.of("UTC")

            override fun random(
                lo: Double,
                hi: Double,
            ): Double = lo
        }

    /** 工具调用时的钩子（模拟运行中应用修订）。 */
    var onToolCall: (suspend (String) -> Unit)? = null

    /** 节点开始前的钩子（模拟节点之间的屏幕变化）。 */
    var onBeforeNode: ((String) -> Unit)? = null

    /** 最近一次运行的解释器（用于运行中调用 applyPatch）。 */
    var lastInterpreter: FlowInterpreter? = null

    private val ports: FlowPorts =
        FlowPorts(
            tools =
                object : ToolPort {
                    override suspend fun call(
                        tool: String,
                        args: JsonObject,
                        nodeId: String,
                    ): ToolCallResult {
                        val text = (args["text"] as? JsonPrimitive)?.content.orEmpty()
                        toolCalls += "$tool:$text"
                        onToolCall?.invoke(tool)
                        if (toolFailuresLeft > 0) {
                            toolFailuresLeft--
                            return ToolCallResult(false, "boom", errorCode = toolErrorCode)
                        }
                        return ToolCallResult(true, "ok")
                    }

                    override fun accessOf(tool: String): ToolAccess = ToolAccess.READ_SYSTEM
                },
            observe =
                object : ObservePort {
                    override suspend fun observe(mode: String?): Observed {
                        observeCount++
                        return Observed(screen(), screenText)
                    }

                    override suspend fun screenshot(region: List<Int>?): String = "img://run/1"
                },
            llm =
                object : LlmPort {
                    override suspend fun complete(call: LlmCall): LlmReply = LlmReply("true", 1, 1)
                },
            api =
                object : ApiPort {
                    override suspend fun call(call: ApiCall): ApiReply = error("not used")
                },
            agent =
                object : AgentPort {
                    override suspend fun run(
                        nodeId: String,
                        goal: String,
                        tools: List<String>?,
                        maxSteps: Int?,
                        maxDurationMs: Long?,
                        role: String?,
                        context: AgentContext?,
                    ): AgentResult {
                        agentGoals += goal
                        context?.let { agentContexts += it }
                        onAgentRun?.invoke()
                        return AgentResult(agentOk, "done", null, null)
                    }
                },
            state =
                object : StatePort {
                    override suspend fun get(
                        scope: StateAddress,
                        key: String,
                    ): JsonElement? = null

                    override suspend fun set(
                        scope: StateAddress,
                        key: String,
                        value: JsonElement,
                        ttlMs: Long?,
                    ) = Unit

                    override suspend fun delete(
                        scope: StateAddress,
                        key: String,
                    ) = Unit
                },
            notifications =
                object : NotificationPort {
                    override suspend fun await(
                        packageName: String?,
                        contains: String?,
                        sender: String?,
                        sinceMs: Long,
                        timeoutMs: Long,
                    ): NotificationInfo? = null
                },
            device =
                object : DevicePort {
                    override fun state() = DeviceState(80, false, "wifi", true, false, foregroundPackage)
                },
            user =
                object : UserPort {
                    override suspend fun confirm(
                        nodeId: String,
                        nodeName: String?,
                        message: String?,
                        timeoutMs: Long,
                    ): Boolean? = true
                },
            flows =
                object : FlowResolver {
                    override suspend fun resolve(nameOrId: String): FlowDefinition? = null
                },
            trace =
                object : TracePort {
                    override suspend fun nodeStarted(start: NodeTraceStart): Long = 1

                    override suspend fun nodeFinished(
                        handle: Long,
                        end: NodeTraceEnd,
                    ) = Unit

                    override suspend fun log(
                        level: String,
                        message: String,
                    ) {
                        logs += "$level:$message"
                    }

                    override suspend fun counters(delta: RunCounters) = Unit
                },
            control =
                object : ControlPort {
                    override suspend fun beforeNode(
                        nodeId: String,
                        nodeName: String?,
                    ) {
                        onBeforeNode?.invoke(nodeId)
                    }

                    override suspend fun pauseOnFailure(
                        reason: PauseReason,
                        nodeId: String,
                        detail: String?,
                    ) = PauseResolution.SKIP_NODE

                    override suspend fun pauseOnConsecutive(
                        count: Int,
                        nodeId: String,
                    ) = PauseResolution.RETRY_NODE

                    override suspend fun pauseOnTool(
                        control: ToolControl,
                        nodeId: String,
                    ) = PauseResolution.SKIP_NODE
                },
            clock = clock,
        )

    /** 当前屏幕模型。 */
    fun screen(): ScreenModel =
        ScreenModel(
            mode = ObserveMode.A11Y,
            windows =
                listOf(
                    ScreenWindow(
                        app = foregroundPackage,
                        page = "首页",
                        active = true,
                        kids = listOf(ScreenNode(tag = "text", text = screenText, bounds = Rect(0, 0, 100, 100))),
                    ),
                ),
            quality = ScreenQuality(nodes = 1, interactive = 0),
        )

    /** 跑一段流程。 */
    fun run(
        steps: List<Node>,
        settings: FlowSettings = FlowSettings(),
        precondition: FlowPreconditionSection = FlowPreconditionSection(pollMs = 50),
        recovery: FlowRecoverySection = FlowRecoverySection(),
        contract: FlowContractSection = FlowContractSection(),
    ): FlowRunResult {
        val flow = FlowDefinition(id = "f1", name = "t", steps = steps, settings = settings)
        return runBlocking {
            val interpreter =
                FlowInterpreter(
                    ports,
                    // 关掉观测缓存：测试会在节点之间改 screenText，缓存会把变化吞掉。
                    FlowRunSection(observeCacheMs = 0),
                    FlowLlmSection(),
                    FlowAgentSection(),
                    recovery,
                    precondition,
                    contract,
                )
            lastInterpreter = interpreter
            interpreter.run(FlowRunInput("r1", flow, JsonObject(emptyMap()), JsonObject(emptyMap()), null, "manual"))
        }
    }

    /** 造一个带条件的工具节点。 */
    fun tool(
        id: String = "t",
        text: String = "x",
        pre: Precondition? = null,
        onFail: OnFail? = null,
        expect: Expect? = null,
    ) = ToolNode(
        id = id,
        tool = "say",
        args = JsonObject(mapOf("text" to JsonPrimitive(text))),
        pre = pre,
        onFail = onFail,
        expect = expect,
    )

    /** 单独求值一个条件，取明细。 */
    fun detail(cond: Condition): CondDetail {
        val screens = ScreenService(ports, FlowRunSection())
        val evaluator = ConditionEvaluator(ports, screens, emptyMap()) { }
        val flow = FlowDefinition(id = "f1", name = "t", steps = emptyList())
        val frame =
            Frame(
                flow,
                JsonObject(emptyMap()),
                JsonObject(emptyMap()),
                RunMeta("r1", 0, "manual", "t", null),
                clock,
                10000,
                0,
                65536,
            )
        return runBlocking { evaluator.detail(cond, frame) }
    }
}
