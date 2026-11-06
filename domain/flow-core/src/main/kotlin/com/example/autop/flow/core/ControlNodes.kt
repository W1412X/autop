@file:Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements") // 循环与状态节点的模式分发，break/continue 对应循环语义

package com.example.autop.flow.core

import com.example.autop.flow.CallNode
import com.example.autop.flow.EndNode
import com.example.autop.flow.EndStatus
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.IfNode
import com.example.autop.flow.LogNode
import com.example.autop.flow.LoopMode
import com.example.autop.flow.LoopNode
import com.example.autop.flow.SetNode
import com.example.autop.flow.StateNode
import com.example.autop.flow.StateOp
import com.example.autop.flow.StateScope
import com.example.autop.flow.SwitchNode
import com.example.autop.flow.core.expr.FlowException
import com.example.autop.flow.core.expr.Values
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 结构与数据节点：if / switch / loop / call / set / state / log / end。 */
internal class ControlNodes(
    private val env: NodeEnv,
) {
    private companion object {
        const val RESUME_PREFIX = "__loop:"
    }

    suspend fun ifNode(
        node: IfNode,
        frame: Frame,
        path: String,
    ): Attempt {
        val yes = env.conditions.eval(node.cond, frame)
        val steps = if (yes) node.thenSteps else node.elseSteps
        val signal = env.host.runSteps(steps, frame, "$path/${if (yes) "then" else "else"}/")
        return Attempt.Ok(signal, JsonPrimitive(yes))
    }

    suspend fun switchNode(
        node: SwitchNode,
        frame: Frame,
        path: String,
    ): Attempt {
        val value = frame.eval(node.on)
        val index = node.cases.indexOfFirst { c -> c.values.any { Values.equal(it, value) } }
        val steps = if (index >= 0) node.cases[index].steps else node.default
        val label = if (index >= 0) "case$index" else "default"
        return Attempt.Ok(env.host.runSteps(steps, frame, "$path/$label/"), value)
    }

    suspend fun loop(
        node: LoopNode,
        frame: Frame,
        path: String,
    ): Attempt {
        val items =
            if (node.mode ==
                LoopMode.EACH
            ) {
                Values.requireList(frame.eval(requireNotNull(node.items) { "each 需要 items" }), "loop.items")
            } else {
                null
            }
        val total =
            if (node.mode ==
                LoopMode.COUNT
            ) {
                Values.requireNum(frame.eval(requireNotNull(node.count) { "count 需要次数" }), "loop.count").toLong()
            } else {
                null
            }
        val savedItem = frame.vars[node.itemVar]
        val savedIndex = frame.vars[node.indexVar]
        val endAt = node.maxDurationMs?.takeIf { it > 0 }?.let { env.ports.clock.nowMs() + it }
        var i = resumeIndex(node, frame)
        var result: Attempt = Attempt.Ok()
        while (true) {
            yield()
            if (shouldStop(node, frame, items, total, i, endAt)) break
            frame.vars[node.indexVar] = JsonPrimitive(i)
            items?.let { frame.vars[node.itemVar] = it[i.toInt()] }
            val signal = env.host.runSteps(node.steps, frame, "$path[$i]/")
            val stop = bodySignal(node, signal)
            if (stop != null) {
                result = Attempt.Ok(stop)
                break
            }
            i++
            saveProgress(node, frame, i)
            if (node.mode == LoopMode.UNTIL && node.cond != null && env.conditions.eval(node.cond!!, frame)) break
            node.delayMs?.let { delay(env.rangeMs(it)) }
        }
        restore(frame, node.itemVar, savedItem)
        restore(frame, node.indexVar, savedIndex)
        clearProgress(node, frame)
        return result
    }

    /** 循环体信号 → 是否结束循环；返回 null 表示继续下一轮。 */
    private fun bodySignal(
        node: LoopNode,
        signal: Signal,
    ): Signal? =
        when (signal) {
            Signal.Next, Signal.Continue -> null
            Signal.Break -> Signal.Next
            is Signal.Fail -> if (node.continueOnFail) null else signal
            is Signal.End -> signal
        }

    private suspend fun shouldStop(
        node: LoopNode,
        frame: Frame,
        items: List<JsonElement>?,
        total: Long?,
        i: Long,
        endAt: Long?,
    ): Boolean {
        val cap = node.maxIterations
        if (cap != null && cap > 0 && i >= cap) return true
        if (endAt != null && env.ports.clock.nowMs() >= endAt) return true
        return when (node.mode) {
            LoopMode.COUNT -> i >= (total ?: 0)
            LoopMode.EACH -> i >= (items?.size ?: 0)
            LoopMode.WHILE -> !env.conditions.eval(requireNotNull(node.cond) { "while 需要 cond" }, frame)
            LoopMode.UNTIL, LoopMode.FOREVER -> false
        }
    }

    private fun restore(
        frame: Frame,
        name: String,
        saved: JsonElement?,
    ) {
        if (saved == null) frame.vars.remove(name) else frame.vars[name] = saved
    }

    private suspend fun resumeIndex(
        node: LoopNode,
        frame: Frame,
    ): Long {
        val key = node.resumeKey ?: return 0
        if (!env.input.options.resume) return 0
        val saved = env.ports.state.get(env.flowScope(frame), RESUME_PREFIX + key)
        return Values.numOrNull(saved)?.toLong() ?: 0
    }

    private suspend fun saveProgress(
        node: LoopNode,
        frame: Frame,
        next: Long,
    ) {
        val key = node.resumeKey ?: return
        env.ports.state.set(env.flowScope(frame), RESUME_PREFIX + key, JsonPrimitive(next), null)
    }

    private suspend fun clearProgress(
        node: LoopNode,
        frame: Frame,
    ) {
        val key = node.resumeKey ?: return
        env.ports.state.delete(env.flowScope(frame), RESUME_PREFIX + key)
    }

    suspend fun call(
        node: CallNode,
        frame: Frame,
    ): Attempt {
        val name = frame.templates.renderString(node.flow)
        val target =
            env.ports.flows.resolve(name)
                ?: return Attempt.Failed(FlowErrorCodes.NOT_FOUND, "找不到子流程：$name")
        if (frame.depth + 1 > env.runCfg.depthGuard) {
            return Attempt.Failed(FlowErrorCodes.DEPTH_EXCEEDED, "子流程嵌套超过保护值 ${env.runCfg.depthGuard}")
        }
        val params = env.renderObject(frame, node.args)
        env.screens.markDirty(action = true)
        val signal = env.host.runSubflow(target, params, frame.depth + 1)
        env.screens.markDirty(action = true)
        return when (signal) {
            is Signal.End ->
                if (signal.success) {
                    val out = signal.outputs ?: JsonObject(emptyMap())
                    frame.store(node.out, out)
                    Attempt.Ok(output = out, text = signal.message, input = params)
                } else {
                    Attempt.Failed(FlowErrorCodes.ENDED_FAILURE, signal.message ?: "子流程以失败结束", params)
                }
            is Signal.Fail -> Attempt.Failed(signal.code, signal.message, params)
            else -> {
                val out = JsonObject(emptyMap())
                frame.store(node.out, out)
                Attempt.Ok(output = out, input = params)
            }
        }
    }

    fun set(
        node: SetNode,
        frame: Frame,
    ): Attempt {
        val assigned = LinkedHashMap<String, JsonElement>()
        for ((name, expr) in node.vars) {
            val value = frame.eval(expr)
            frame.assign(name, value)
            assigned[name] = value
        }
        return Attempt.Ok(output = JsonObject(assigned))
    }

    suspend fun state(
        node: StateNode,
        frame: Frame,
    ): Attempt {
        val scope = if (node.scope == StateScope.TASK) env.taskScope(frame) else env.flowScope(frame)
        val baseKey = frame.templates.renderString(node.key)
        val key = if (node.resetDaily) "$baseKey@${Values.str(frame.eval("dayKey()"))}" else baseKey
        val out: JsonElement? =
            when (node.op) {
                StateOp.GET -> env.ports.state.get(scope, key) ?: node.default?.let { frame.eval(it) } ?: JsonNull
                StateOp.SET -> {
                    val value =
                        frame.eval(
                            node.value ?: throw FlowException(FlowErrorCodes.INVALID, "state.set 需要 value"),
                        )
                    env.ports.state.set(scope, key, value, node.ttlMs)
                    value
                }
                StateOp.INC -> {
                    val delta = node.value?.let { Values.requireNum(frame.eval(it), "state.inc") } ?: 1.0
                    val current = Values.numOrNull(env.ports.state.get(scope, key)) ?: 0.0
                    val next = Values.num(current + delta)
                    env.ports.state.set(scope, key, next, node.ttlMs)
                    next
                }
                StateOp.DELETE -> {
                    env.ports.state.delete(scope, key)
                    null
                }
            }
        if (out != null) frame.store(node.out, out)
        return Attempt.Ok(output = out)
    }

    suspend fun log(
        node: LogNode,
        frame: Frame,
    ): Attempt {
        val message = frame.templates.renderString(node.message)
        return Attempt.Ok(output = JsonPrimitive(message), text = message)
    }

    fun end(
        node: EndNode,
        frame: Frame,
    ): Attempt {
        val outputs = JsonObject(node.outputs.mapValues { frame.eval(it.value) })
        val message = node.message?.let { frame.templates.renderString(it) }
        return Attempt.Ok(
            Signal.End(
                node.status == EndStatus.SUCCESS,
                message,
                outputs.takeIf {
                    it.isNotEmpty()
                },
            ),
            outputs,
            message,
        )
    }
}
