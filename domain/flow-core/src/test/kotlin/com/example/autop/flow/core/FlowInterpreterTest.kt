package com.example.autop.flow.core

import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolControl
import com.example.autop.core.config.FlowAgentSection
import com.example.autop.core.config.FlowLlmSection
import com.example.autop.core.config.FlowRunSection
import com.example.autop.device.DeviceState
import com.example.autop.device.NotificationInfo
import com.example.autop.flow.BreakNode
import com.example.autop.flow.Condition
import com.example.autop.flow.EndNode
import com.example.autop.flow.FinalAction
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowRunOptions
import com.example.autop.flow.FlowSettings
import com.example.autop.flow.IfNode
import com.example.autop.flow.LoopMode
import com.example.autop.flow.LoopNode
import com.example.autop.flow.Node
import com.example.autop.flow.OnFail
import com.example.autop.flow.PauseResolution
import com.example.autop.flow.RetrySpec
import com.example.autop.flow.SetNode
import com.example.autop.flow.ToolNode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class FlowInterpreterTest {
    private val calls = mutableListOf<String>()
    private var failuresLeft = 0

    private val ports =
        FlowPorts(
            tools =
                object : ToolPort {
                    override suspend fun call(
                        tool: String,
                        args: JsonObject,
                        nodeId: String,
                    ): ToolCallResult {
                        calls += "$tool:${args["text"]?.let { (it as JsonPrimitive).content }.orEmpty()}"
                        if (failuresLeft > 0) {
                            failuresLeft--
                            return ToolCallResult(false, "boom", errorCode = "tool.fail")
                        }
                        return ToolCallResult(true, "ok")
                    }

                    override fun accessOf(tool: String): ToolAccess = ToolAccess.READ_SYSTEM
                },
            observe =
                object : ObservePort {
                    override suspend fun observe(mode: String?): Observed = error("not used")

                    override suspend fun screenshot(region: List<Int>?): String = error("not used")
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
                        context: AgentContext?,
                    ) = AgentResult(true, "done", null, null)
                },
            state =
                object : StatePort {
                    override suspend fun get(
                        scope: StateAddress,
                        key: String,
                    ) = null

                    override suspend fun set(
                        scope: StateAddress,
                        key: String,
                        value: kotlinx.serialization.json.JsonElement,
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
                    override fun state() = DeviceState(80, false, "wifi", true, false, null)
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
                    ) = Unit

                    override suspend fun counters(delta: RunCounters) = Unit
                },
            control =
                object : ControlPort {
                    override suspend fun beforeNode(
                        nodeId: String,
                        nodeName: String?,
                    ) = Unit

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
            clock =
                object : FlowClock {
                    override fun nowMs(): Long = 0

                    override val zone: ZoneId = ZoneId.of("UTC")

                    override fun random(
                        lo: Double,
                        hi: Double,
                    ): Double = lo
                },
        )

    private fun run(
        steps: List<Node>,
        settings: FlowSettings = FlowSettings(),
    ): FlowRunResult {
        val flow = FlowDefinition(id = "f1", name = "t", steps = steps, settings = settings)
        return runBlocking {
            FlowInterpreter(ports, FlowRunSection(), FlowLlmSection(), FlowAgentSection())
                .run(FlowRunInput("r1", flow, JsonObject(emptyMap()), JsonObject(emptyMap()), null, "manual"))
        }
    }

    private fun tool(
        id: String,
        text: String,
        onFail: OnFail? = null,
    ) = ToolNode(id = id, tool = "say", args = JsonObject(mapOf("text" to JsonPrimitive(text))), onFail = onFail)

    @Test
    fun `count loop runs body with index variable and break leaves loop`() {
        val loop =
            LoopNode(
                id = "l",
                mode = LoopMode.COUNT,
                count = "5",
                steps =
                    listOf(
                        tool("t", "i={{ i }}"),
                        IfNode(id = "if", cond = Condition.Expr("i >= 2"), thenSteps = listOf(BreakNode(id = "b"))),
                    ),
            )
        val r = run(listOf(loop))
        assertEquals(RunOutcome.SUCCEEDED, r.status)
        assertEquals(listOf("say:i=0", "say:i=1", "say:i=2"), calls)
    }

    @Test
    fun `effective max duration takes smaller of settings and caller cap`() {
        // 工具预算截断语义（run_flow）：定义上限与调用方上限取更小者；<=0 视为不限。
        assertEquals(5_000L, FlowRunOptions(maxDurationCapMs = 5_000).effectiveMaxDurationMs(10_000))
        assertEquals(3_000L, FlowRunOptions(maxDurationCapMs = 5_000).effectiveMaxDurationMs(3_000))
        assertEquals(5_000L, FlowRunOptions(maxDurationCapMs = 5_000).effectiveMaxDurationMs(null))
        assertEquals(3_000L, FlowRunOptions().effectiveMaxDurationMs(3_000))
        assertEquals(null, FlowRunOptions().effectiveMaxDurationMs(null))
        // settings <=0 视为不限，调用方上限仍然生效。
        assertEquals(5_000L, FlowRunOptions(maxDurationCapMs = 5_000).effectiveMaxDurationMs(0))
    }

    @Test
    fun `retry then succeed does not fail the run`() {
        failuresLeft = 2
        val r = run(listOf(tool("t", "x", OnFail(retry = RetrySpec(max = 3)))))
        assertEquals(RunOutcome.SUCCEEDED, r.status)
        assertEquals(3, calls.size)
    }

    @Test
    fun `final stop fails the run with the tool error code`() {
        failuresLeft = 5
        val r = run(listOf(tool("t", "x"), tool("t2", "y")))
        assertEquals(RunOutcome.FAILED, r.status)
        assertEquals("tool.fail", r.errorCode)
        assertEquals(1, calls.size)
    }

    @Test
    fun `final skip continues with next node`() {
        failuresLeft = 1
        val r = run(listOf(tool("t", "x", OnFail(final = FinalAction.SKIP)), tool("t2", "y")))
        assertEquals(RunOutcome.SUCCEEDED, r.status)
        assertEquals(2, calls.size)
    }

    @Test
    fun `set evaluates in order and end returns outputs`() {
        val r =
            run(
                listOf(
                    SetNode(id = "s", vars = linkedMapOf("a" to "1", "b" to "a + 1")),
                    EndNode(id = "e", outputs = mapOf("sum" to "a + b")),
                ),
            )
        assertEquals(RunOutcome.SUCCEEDED, r.status)
        assertEquals(JsonPrimitive(3), r.outputs?.get("sum"))
    }

    @Test
    fun `undefined variable fails the run with stable code`() {
        val r = run(listOf(SetNode(id = "s", vars = mapOf("a" to "nope + 1"))))
        assertEquals(RunOutcome.FAILED, r.status)
        assertEquals(FlowErrorCodes.VAR_UNDEFINED, r.errorCode)
        assertTrue(calls.isEmpty())
    }
}
