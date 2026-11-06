@file:Suppress("CyclomaticComplexMethod") // 节点类型名映射：一个类型一个分支

package com.example.autop.flow.core

import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ToolAccess
import com.example.autop.core.config.FlowAgentSection
import com.example.autop.core.config.FlowLlmSection
import com.example.autop.core.config.FlowRecoverySection
import com.example.autop.core.config.FlowRunSection
import com.example.autop.flow.AfterRecovery
import com.example.autop.flow.AgentNode
import com.example.autop.flow.ApiNode
import com.example.autop.flow.BreakNode
import com.example.autop.flow.CallNode
import com.example.autop.flow.ContinueNode
import com.example.autop.flow.EndNode
import com.example.autop.flow.FinalAction
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowRunOptions
import com.example.autop.flow.Handoff
import com.example.autop.flow.IfNode
import com.example.autop.flow.LlmNode
import com.example.autop.flow.LogNode
import com.example.autop.flow.LoopNode
import com.example.autop.flow.Node
import com.example.autop.flow.NodeStatus
import com.example.autop.flow.ObserveNode
import com.example.autop.flow.OnFail
import com.example.autop.flow.PauseResolution
import com.example.autop.flow.ReadNode
import com.example.autop.flow.RetryStrategy
import com.example.autop.flow.SetNode
import com.example.autop.flow.StateNode
import com.example.autop.flow.SwitchNode
import com.example.autop.flow.ToolNode
import com.example.autop.flow.TraceLevel
import com.example.autop.flow.WaitNode
import com.example.autop.flow.core.expr.FlowException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一次运行的输入。 */
public data class FlowRunInput(
    val runId: String,
    val flow: FlowDefinition,
    val params: JsonObject,
    val trigger: JsonObject,
    val taskId: String?,
    val origin: String,
    val options: FlowRunOptions = FlowRunOptions(),
)

/** 一次运行的结果；取消以 CancellationException 传播，由调用方（FlowSession）转换。 */
public data class FlowRunResult(
    val status: RunOutcome,
    val errorCode: String?,
    val message: String?,
    val outputs: JsonObject?,
    val nodesExecuted: Int,
)

/** 节点之间传播的控制信号（不用异常做控制流）。 */
internal sealed interface Signal {
    data object Next : Signal

    data object Break : Signal

    data object Continue : Signal

    data class End(
        val success: Boolean,
        val message: String?,
        val outputs: JsonObject?,
    ) : Signal

    data class Fail(
        val code: String,
        val message: String,
    ) : Signal
}

/** 单次尝试的结果。 */
internal sealed interface Attempt {
    data class Ok(
        val signal: Signal = Signal.Next,
        val output: JsonElement? = null,
        val text: String? = null,
        val input: JsonElement? = null,
        val skipped: Boolean = false,
    ) : Attempt

    data class Failed(
        val code: String,
        val message: String,
        val input: JsonElement? = null,
    ) : Attempt
}

/**
 * 流程解释器（docs/design/flows.md §8）：纯 Kotlin 协程，不依赖 Android。
 * 每个节点：暂停检查 → enabled → 确认 → 执行（带超时）→ 失败处理 → 记录 → 更新 last。
 */
public class FlowInterpreter(
    private val ports: FlowPorts,
    private val runCfg: FlowRunSection,
    private val llmCfg: FlowLlmSection,
    private val agentCfg: FlowAgentSection,
    private val recoveryCfg: FlowRecoverySection = FlowRecoverySection(),
) {
    private lateinit var input: FlowRunInput
    private lateinit var meta: RunMeta
    private lateinit var screens: ScreenService
    private lateinit var conditions: ConditionEvaluator
    private lateinit var nodes: NodeRunner
    private val host =
        object : NodeHost {
            override suspend fun runSteps(
                steps: List<Node>,
                frame: Frame,
                prefix: String,
            ): Signal = runSteps(steps, 0, frame, prefix)

            override suspend fun runSubflow(
                flow: FlowDefinition,
                params: JsonObject,
                depth: Int,
            ): Signal {
                val frame = newFrame(flow, ParamBinder.bind(flow.params, params), JsonObject(emptyMap()), depth)
                return runSteps(flow.steps, 0, frame, flow.id + ":")
            }
        }

    private var deadline: Long? = null
    private var executed = 0
    private var consecutiveFailures = 0
    private val lastOk = HashMap<String, Boolean>()
    private val tokens = TokenBudget()

    /** 执行流程。参数/表达式等受控错误转为 FAILED 结果，不抛出。 */
    public suspend fun run(input: FlowRunInput): FlowRunResult {
        this.input = input
        meta = RunMeta(input.runId, ports.clock.nowMs(), input.origin, input.flow.name, input.taskId)
        screens = ScreenService(ports, runCfg)
        conditions =
            ConditionEvaluator(ports, screens, lastOk) {
                tokens.add(it)
                ports.trace.counters(
                    RunCounters(
                        llmCalls = 1,
                        llmTokens =
                            it.promptTokens + it.completionTokens,
                    ),
                )
            }
        nodes = NodeRunner(ports, runCfg, llmCfg, agentCfg, screens, conditions, tokens, input, host, recoveryCfg)
        deadline =
            input.flow.settings.maxDurationMs
                ?.takeIf { it > 0 }
                ?.let { meta.startedAt + it }
        return try {
            val frame = newFrame(input.flow, ParamBinder.bind(input.flow.params, input.params), input.trigger, 0)
            input.options.overrides.forEach { (k, v) -> frame.vars[k] = v }
            val signal = start(frame)
            toResult(signal)
        } catch (e: FlowException) {
            FlowRunResult(RunOutcome.FAILED, e.code, e.message, null, executed)
        }
    }

    private fun newFrame(
        flow: FlowDefinition,
        params: JsonObject,
        trigger: JsonObject,
        depth: Int,
    ): Frame = Frame(flow, params, trigger, meta, ports.clock, runCfg.exprMaxSteps, depth, runCfg.maxVarBytes)

    private suspend fun start(frame: Frame): Signal {
        val opts = input.options
        val only = opts.onlyNode
        val startAt = opts.startAt
        return when {
            only != null -> {
                val chain = locate(frame.flow.steps, only) ?: throw missing(only)
                val (list, idx) = chain.last()
                runSteps(listOf(list[idx]), 0, frame, "")
            }
            startAt != null -> {
                val chain = locate(frame.flow.steps, startAt) ?: throw missing(startAt)
                runFrom(chain, frame)
            }
            else -> runSteps(frame.flow.steps, 0, frame, "")
        }
    }

    private fun missing(id: String) = FlowException(FlowErrorCodes.NOT_FOUND, "找不到节点：$id")

    private fun toResult(signal: Signal): FlowRunResult =
        when (signal) {
            is Signal.End ->
                if (signal.success) {
                    FlowRunResult(RunOutcome.SUCCEEDED, null, signal.message, signal.outputs, executed)
                } else {
                    FlowRunResult(
                        RunOutcome.FAILED,
                        FlowErrorCodes.ENDED_FAILURE,
                        signal.message,
                        signal.outputs,
                        executed,
                    )
                }
            is Signal.Fail ->
                if (signal.code == FlowErrorCodes.CANCELLED) {
                    FlowRunResult(RunOutcome.CANCELLED, signal.code, signal.message, null, executed)
                } else {
                    FlowRunResult(RunOutcome.FAILED, signal.code, signal.message, null, executed)
                }
            Signal.Next, Signal.Break, Signal.Continue ->
                FlowRunResult(
                    RunOutcome.SUCCEEDED,
                    null,
                    null,
                    null,
                    executed,
                )
        }

    // ---- 起点定位（startAt）-------------------------------------------------------------------

    private fun locate(
        steps: List<Node>,
        id: String,
    ): List<Pair<List<Node>, Int>>? {
        for ((i, node) in steps.withIndex()) {
            if (node.id == id) return listOf(steps to i)
            for (children in childLists(node)) {
                val sub = locate(children, id) ?: continue
                return listOf(steps to i) + sub
            }
        }
        return null
    }

    private fun childLists(node: Node): List<List<Node>> =
        when (node) {
            is IfNode -> listOf(node.thenSteps, node.elseSteps)
            is SwitchNode -> node.cases.map { it.steps } + listOf(node.default)
            is LoopNode -> listOf(node.steps)
            else -> emptyList()
        }

    private suspend fun runFrom(
        chain: List<Pair<List<Node>, Int>>,
        frame: Frame,
    ): Signal {
        var signal: Signal = Signal.Next
        for (level in chain.indices.reversed()) {
            val (list, idx) = chain[level]
            val from = if (level == chain.lastIndex) idx else idx + 1
            signal = runSteps(list, from, frame, "")
            if (signal is Signal.Break || signal is Signal.Continue) signal = Signal.Next
            if (signal !is Signal.Next) break
        }
        return signal
    }

    // ---- 步骤执行 -----------------------------------------------------------------------------

    private suspend fun runSteps(
        steps: List<Node>,
        from: Int,
        frame: Frame,
        prefix: String,
    ): Signal {
        for (i in from until steps.size) {
            val signal = runNode(steps[i], frame, prefix)
            if (signal !is Signal.Next) return signal
        }
        return Signal.Next
    }

    private suspend fun runNode(
        node: Node,
        frame: Frame,
        prefix: String,
    ): Signal {
        if (!node.enabled) return Signal.Next
        deadlineSignal()?.let { return it }
        ports.control.beforeNode(node.id, node.name)
        val path = prefix + node.id
        nodes.confirm(node, frame)?.let { return it }
        nodes.pace(frame, node, executed > 0)
        val level = node.trace ?: frame.flow.settings.trace
        val start = NodeTraceStart(frame.flow.id, node.id, typeName(node), node.name, path)
        val handle = if (level == TraceLevel.FULL) ports.trace.nodeStarted(start) else -1
        val resolved = recover(node, frame, path)
        executed++
        val ended =
            NodeTraceEnd(
                input = resolved.input,
                status = resolved.status,
                errorCode = resolved.errorCode,
                message = resolved.message,
                output = resolved.output,
                attempts = resolved.attempts,
                llmTokens = 0,
            )
        writeTrace(level, handle, start, ended)
        afterNode(node, frame, resolved)
        val extra = consecutiveSignal(resolved, node, frame)
        return if (resolved.signal is Signal.Next && extra != null) extra else resolved.signal
    }

    private suspend fun writeTrace(
        level: TraceLevel,
        handle: Long,
        start: NodeTraceStart,
        end: NodeTraceEnd,
    ) {
        when {
            level == TraceLevel.NONE -> Unit
            level == TraceLevel.FULL -> ports.trace.nodeFinished(handle, end)
            end.status == NodeStatus.FAILED || end.status == NodeStatus.RECOVERED -> {
                val h = ports.trace.nodeStarted(start)
                ports.trace.nodeFinished(h, end)
            }
        }
    }

    private fun afterNode(
        node: Node,
        frame: Frame,
        r: Resolved,
    ) {
        val ok = r.status != NodeStatus.FAILED
        lastOk[node.id] = ok
        frame.last =
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(ok),
                    "error" to (r.message?.takeIf { !ok }?.let { JsonPrimitive(it) } ?: JsonNull),
                    "text" to JsonPrimitive(r.text.orEmpty()),
                ),
            )
        screens.nodeFinished(node.id)
    }

    /** 节点最终失败（STOP 以外的收尾）累计到阈值时暂停（§7.1）。 */
    private suspend fun consecutiveSignal(
        r: Resolved,
        node: Node,
        frame: Frame,
    ): Signal? {
        val failedFinally = r.status == NodeStatus.FAILED
        if (!failedFinally) {
            consecutiveFailures = 0
            return null
        }
        consecutiveFailures++
        val limit = frame.flow.settings.pauseAfterConsecutiveFailures
        if (limit <= 0 || consecutiveFailures < limit) return null
        val resolution = ports.control.pauseOnConsecutive(consecutiveFailures, node.id)
        consecutiveFailures = 0
        return if (resolution ==
            PauseResolution.END_RUN
        ) {
            Signal.Fail(FlowErrorCodes.CANCELLED, "用户在连续失败后结束了运行")
        } else {
            null
        }
    }

    private fun deadlineSignal(): Signal? {
        val limit = deadline ?: return null
        return if (ports.clock.nowMs() > limit) Signal.Fail(FlowErrorCodes.MAX_DURATION, "超过流程最长运行时间") else null
    }

    // ---- 失败处理（retry → handoff → steps → final）--------------------------------------------

    internal class Resolved(
        val signal: Signal,
        val status: NodeStatus,
        val output: JsonElement?,
        val text: String?,
        val input: JsonElement?,
        val errorCode: String?,
        val message: String?,
        val attempts: Int,
    )

    @Suppress("ReturnCount") // 失败处理阶梯：每个阶段成功即返回，是流程语义
    private suspend fun recover(
        node: Node,
        frame: Frame,
        path: String,
    ): Resolved {
        var attempts = 1
        var attempt = nodes.perform(node, frame, path)
        if (attempt is Attempt.Ok) return okResolved(attempt, attempts, NodeStatus.OK)
        val policy = node.onFail ?: frame.flow.settings.onFail ?: OnFail()
        val retry = policy.retry
        val toolNode = node as? ToolNode
        val escalating = toolNode != null && escalates(toolNode, frame, policy)
        if (toolNode != null && escalating) {
            // 恢复阶梯：第 k 次重试先执行阶梯第 k 个适用动作（docs/design/flows-recording-recovery.md R4）。
            val ladder = retry?.ladder ?: recoveryCfg.ladder
            val max = retry?.max ?: recoveryCfg.implicitMaxAttempts
            for (k in 1..max) {
                attempts++
                val code = (attempt as Attempt.Failed).code
                nodes.recoveryStep(toolNode, frame, ladder, k, code)
                nodes.pause(retry?.delayMs)
                attempt = nodes.perform(node, frame, path)
                if (attempt is Attempt.Ok) return okResolved(attempt, attempts, NodeStatus.OK)
            }
        } else if (retry != null) {
            repeat(retry.max) {
                nodes.pause(retry.delayMs)
                attempts++
                attempt = nodes.perform(node, frame, path)
                if (attempt is Attempt.Ok) return okResolved(attempt, attempts, NodeStatus.OK)
            }
        }
        var failed = attempt as Attempt.Failed
        if (escalating) {
            failed =
                Attempt.Failed(FlowErrorCodes.RECOVERY_EXHAUSTED, "恢复阶梯已用尽：${failed.message}", failed.input)
        }
        setFailureLast(frame, failed)
        val recovered = tryRecover(node, frame, path, policy, escalating)
        if (recovered != null) return okResolved(recovered.first, attempts + recovered.second, NodeStatus.RECOVERED)
        return finalAction(node, frame, path, policy, failed, attempts)
    }

    /**
     * 是否按恢复阶梯升级：显式 `retry.strategy=escalate`，或工具节点未写任何 onFail 且工具会改变屏幕（隐式升级）。
     */
    private fun escalates(
        node: ToolNode,
        frame: Frame,
        policy: OnFail,
    ): Boolean {
        if (!recoveryCfg.enabled) return false
        val retry = policy.retry
        if (retry != null) return retry.strategy == RetryStrategy.ESCALATE
        return node.onFail == null &&
            frame.flow.settings.onFail == null &&
            recoveryCfg.implicitMaxAttempts > 0 &&
            ports.tools.accessOf(node.tool) == ToolAccess.WRITE_SCREEN
    }

    private fun setFailureLast(
        frame: Frame,
        failed: Attempt.Failed,
    ) {
        frame.last =
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(false),
                    "error" to JsonPrimitive(failed.message),
                    "text" to JsonPrimitive(""),
                ),
            )
    }

    private fun okResolved(
        a: Attempt.Ok,
        attempts: Int,
        status: NodeStatus,
    ): Resolved =
        Resolved(
            a.signal,
            if (a.skipped) NodeStatus.SKIPPED else status,
            a.output,
            a.text,
            a.input,
            null,
            null,
            attempts,
        )

    /** handoff / steps；成功返回 (结果, 额外尝试次数)，未配置或失败返回 null。 */
    private suspend fun tryRecover(
        node: Node,
        frame: Frame,
        path: String,
        policy: OnFail,
        escalating: Boolean,
    ): Pair<Attempt.Ok, Int>? {
        var extra = 0
        // 隐式交回：阶梯耗尽且节点无 handoff 时，目标文本由智能体端口按模板生成（空 goal）。
        val implicit =
            if (escalating &&
                recoveryCfg.implicitHandoff &&
                policy.handoff == null
            ) {
                Handoff(goal = "")
            } else {
                null
            }
        val handoff = policy.handoff ?: implicit
        if (handoff != null) {
            ports.trace.counters(RunCounters(handoffs = 1))
            screens.markDirty(action = true)
            val goal = frame.templates.renderString(handoff.goal)
            val r =
                ports.agent.run(
                    node.id,
                    goal,
                    handoff.tools,
                    handoff.maxSteps ?: agentCfg.maxSteps,
                    handoff.maxDurationMs,
                    handoff.role,
                )
            extra++
            if (r.ok) afterRecovered(node, frame, path, policy)?.let { return it to extra }
        }
        if (policy.steps.isNotEmpty()) {
            screens.markDirty(action = true)
            val signal = runSteps(policy.steps, 0, frame, "$path.onFail/")
            extra++
            if (signal !is Signal.Fail) afterRecovered(node, frame, path, policy)?.let { return it to extra }
        }
        return null
    }

    private suspend fun afterRecovered(
        node: Node,
        frame: Frame,
        path: String,
        policy: OnFail,
    ): Attempt.Ok? {
        if (policy.after == AfterRecovery.CONTINUE) return Attempt.Ok()
        return nodes.perform(node, frame, path) as? Attempt.Ok
    }

    private suspend fun finalAction(
        node: Node,
        frame: Frame,
        path: String,
        policy: OnFail,
        failed: Attempt.Failed,
        attempts: Int,
    ): Resolved {
        val failure =
            Resolved(
                Signal.Fail(failed.code, failed.message),
                NodeStatus.FAILED,
                null,
                null,
                failed.input,
                failed.code,
                failed.message,
                attempts,
            )
        return when (policy.final) {
            FinalAction.STOP -> failure
            FinalAction.SKIP -> failure.copyWith(Signal.Next)
            FinalAction.SUCCEED -> Resolved(Signal.Next, NodeStatus.OK, null, null, failed.input, null, null, attempts)
            FinalAction.PAUSE -> pauseOnFinal(node, frame, path, failed, failure, attempts)
        }
    }

    private fun Resolved.copyWith(signal: Signal): Resolved =
        Resolved(signal, status, output, text, input, errorCode, message, attempts)

    private suspend fun pauseOnFinal(
        node: Node,
        frame: Frame,
        path: String,
        failed: Attempt.Failed,
        failure: Resolved,
        attempts: Int,
    ): Resolved {
        var count = attempts
        while (true) {
            when (ports.control.pauseOnFailure(PauseReason.MANUAL, node.id, failed.message)) {
                PauseResolution.END_RUN -> return failure.copyWith(Signal.Fail(FlowErrorCodes.CANCELLED, "用户结束了运行"))
                PauseResolution.SKIP_NODE -> return failure.copyWith(Signal.Next)
                PauseResolution.RETRY_NODE -> {
                    count++
                    val a = nodes.perform(node, frame, path)
                    if (a is Attempt.Ok) return okResolved(a, count, NodeStatus.RECOVERED)
                }
            }
        }
    }

    private fun typeName(node: Node): String =
        when (node) {
            is ToolNode -> "tool"
            is ObserveNode -> "observe"
            is ReadNode -> "read"
            is WaitNode -> "wait"
            is LlmNode -> "llm"
            is IfNode -> "if"
            is SwitchNode -> "switch"
            is LoopNode -> "loop"
            is BreakNode -> "break"
            is ContinueNode -> "continue"
            is CallNode -> "call"
            is AgentNode -> "agent"
            is ApiNode -> "api"
            is SetNode -> "set"
            is StateNode -> "state"
            is LogNode -> "log"
            is EndNode -> "end"
        }
}

/** 一次运行内 LLM 用量累计。 */
internal class TokenBudget {
    var tokens: Long = 0
    var calls: Int = 0

    fun add(reply: LlmReply) {
        tokens += reply.promptTokens + reply.completionTokens
        calls++
    }
}
