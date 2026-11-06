@file:Suppress("CyclomaticComplexMethod") // 节点类型分发表：一个类型一个分支

package com.example.autop.flow.core

import com.example.autop.core.config.FlowAgentSection
import com.example.autop.core.config.FlowLlmSection
import com.example.autop.core.config.FlowRecoverySection
import com.example.autop.core.config.FlowRunSection
import com.example.autop.flow.AgentNode
import com.example.autop.flow.ApiNode
import com.example.autop.flow.BreakNode
import com.example.autop.flow.CallNode
import com.example.autop.flow.Condition
import com.example.autop.flow.ConfirmTimeout
import com.example.autop.flow.ContinueNode
import com.example.autop.flow.DelayRange
import com.example.autop.flow.EndNode
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.IfNode
import com.example.autop.flow.LlmNode
import com.example.autop.flow.LogNode
import com.example.autop.flow.LoopNode
import com.example.autop.flow.Node
import com.example.autop.flow.ObserveNode
import com.example.autop.flow.ReadNode
import com.example.autop.flow.SetNode
import com.example.autop.flow.StateNode
import com.example.autop.flow.SwitchNode
import com.example.autop.flow.ToolNode
import com.example.autop.flow.WaitNode
import com.example.autop.flow.core.expr.FlowException
import com.example.autop.flow.core.recovery.FailureStage
import com.example.autop.flow.core.recovery.RecoveryCatalog
import com.example.autop.flow.core.recovery.RecoveryHints
import com.example.autop.flow.core.recovery.RecoveryRunner
import com.example.autop.flow.core.verify.ActionVerifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 解释器向节点执行器暴露的递归入口。 */
internal interface NodeHost {
    suspend fun runSteps(
        steps: List<Node>,
        frame: Frame,
        prefix: String,
    ): Signal

    suspend fun runSubflow(
        flow: FlowDefinition,
        params: JsonObject,
        depth: Int,
    ): Signal
}

/** 节点执行器共享的环境。 */
internal class NodeEnv(
    val ports: FlowPorts,
    val runCfg: FlowRunSection,
    val llmCfg: FlowLlmSection,
    val agentCfg: FlowAgentSection,
    val screens: ScreenService,
    val conditions: ConditionEvaluator,
    val tokens: TokenBudget,
    val input: FlowRunInput,
    val host: NodeHost,
    val recoveryCfg: FlowRecoverySection,
) {
    val hints = RecoveryHints()
    val verifier = ActionVerifier(ports, screens, recoveryCfg)
    val recovery = RecoveryRunner(RecoveryCatalog.builtin(), ports, screens, recoveryCfg, hints)

    /** 渲染 JSON 对象里的全部模板。 */
    fun renderObject(
        frame: Frame,
        obj: JsonObject,
    ): JsonObject = frame.templates.renderJson(obj) as JsonObject

    fun rangeMs(range: DelayRange): Long =
        if (range.minMs ==
            range.maxMs
        ) {
            range.minMs
        } else {
            ports.clock.random(range.minMs.toDouble(), range.maxMs.toDouble()).toLong()
        }

    fun flowScope(frame: Frame): StateAddress = StateAddress("flow:${frame.flow.id}")

    fun taskScope(frame: Frame): StateAddress = input.taskId?.let { StateAddress("task:$it") } ?: flowScope(frame)
}

/** 节点执行入口：确认、拟人化间隔、超时与按类型分发。 */
internal class NodeRunner(
    ports: FlowPorts,
    runCfg: FlowRunSection,
    llmCfg: FlowLlmSection,
    agentCfg: FlowAgentSection,
    screens: ScreenService,
    conditions: ConditionEvaluator,
    tokens: TokenBudget,
    input: FlowRunInput,
    host: NodeHost,
    recoveryCfg: FlowRecoverySection,
) {
    private val env = NodeEnv(ports, runCfg, llmCfg, agentCfg, screens, conditions, tokens, input, host, recoveryCfg)
    private val control = ControlNodes(env)
    private val screenNodes = ScreenNodes(env)
    private val external = ExternalNodes(env)

    /** 执行前确认；返回 null 继续，[Signal.Next] 跳过该节点，其他信号结束运行。 */
    suspend fun confirm(
        node: Node,
        frame: Frame,
    ): Signal? {
        val c = node.confirm ?: return null
        val message = c.message?.let { frame.templates.renderString(it) }
        val answer = env.ports.user.confirm(node.id, node.name, message, c.timeoutMs)
        if (answer == true) return null
        if (answer == false) return Signal.Next
        return when (c.onTimeout) {
            ConfirmTimeout.PROCEED -> null
            ConfirmTimeout.SKIP -> Signal.Next
            ConfirmTimeout.STOP -> Signal.Fail(FlowErrorCodes.CANCELLED, "确认超时，已结束运行")
        }
    }

    /** 工具节点之间的拟人化间隔。 */
    suspend fun pace(
        frame: Frame,
        node: Node,
        notFirst: Boolean,
    ) {
        val range =
            frame.flow.settings.pacing
                ?.betweenNodesMs ?: return
        if (notFirst && node is ToolNode) delay(env.rangeMs(range))
    }

    /** 执行恢复阶梯第 [attempt] 步；返回执行的动作 id，无适用动作返回 null。 */
    suspend fun recoveryStep(
        node: ToolNode,
        frame: Frame,
        ladder: List<String>,
        attempt: Int,
        errorCode: String,
        stage: FailureStage,
        preKind: Condition? = null,
    ): String? = env.recovery.step(node, frame, ladder, attempt, errorCode, stage, preKind)

    suspend fun pause(range: DelayRange?) {
        if (range == null) return
        val ms = env.rangeMs(range)
        if (ms > 0) delay(ms)
    }

    /** 执行一次尝试；受控错误转为 [Attempt.Failed]。 */
    suspend fun perform(
        node: Node,
        frame: Frame,
        path: String,
    ): Attempt =
        try {
            val timeout = node.timeoutMs
            if (timeout != null && timeout > 0 && node !is WaitNode) {
                withTimeoutOrNull(timeout) { dispatch(node, frame, path) }
                    ?: Attempt.Failed(FlowErrorCodes.NODE_TIMEOUT, "节点执行超过 ${timeout}ms")
            } else {
                dispatch(node, frame, path)
            }
        } catch (e: FlowException) {
            Attempt.Failed(e.code, e.message ?: e.code)
        }

    private suspend fun dispatch(
        node: Node,
        frame: Frame,
        path: String,
    ): Attempt =
        when (node) {
            is ToolNode -> external.tool(node, frame)
            is LlmNode -> external.llm(node, frame)
            is ApiNode -> external.api(node, frame)
            is AgentNode -> external.agent(node, frame)
            is ObserveNode -> screenNodes.observe(node, frame)
            is ReadNode -> screenNodes.read(node, frame)
            is WaitNode -> screenNodes.wait(node, frame)
            is IfNode -> control.ifNode(node, frame, path)
            is SwitchNode -> control.switchNode(node, frame, path)
            is LoopNode -> control.loop(node, frame, path)
            is BreakNode -> Attempt.Ok(Signal.Break)
            is ContinueNode -> Attempt.Ok(Signal.Continue)
            is CallNode -> control.call(node, frame)
            is SetNode -> control.set(node, frame)
            is StateNode -> control.state(node, frame)
            is LogNode -> control.log(node, frame)
            is EndNode -> control.end(node, frame)
        }
}

/** 把表达式结果写入 `out` 变量（若声明）。 */
internal fun Frame.store(
    out: String?,
    value: JsonElement,
) {
    if (!out.isNullOrBlank()) assign(out, value)
}
