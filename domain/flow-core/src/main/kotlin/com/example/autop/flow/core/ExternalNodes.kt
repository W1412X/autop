@file:Suppress("CyclomaticComplexMethod", "LongParameterList") // 对外调用节点的参数装配

package com.example.autop.flow.core

import com.example.autop.agent.ToolAccess
import com.example.autop.flow.AgentNode
import com.example.autop.flow.ApiNode
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.LlmNode
import com.example.autop.flow.LlmOutputType
import com.example.autop.flow.PauseResolution
import com.example.autop.flow.Target
import com.example.autop.flow.ToolNode
import com.example.autop.flow.core.expr.FlowException
import com.example.autop.flow.core.expr.Values
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 流程内 `tool` 节点与 `agent` 节点不得使用的工具（docs/design/flows.md §8.2）。
 * 节点工具（tap_node/scroll_node）也在禁列：id 只对单次观测有效，不能写进流程。
 */
public object FlowToolRules {
    public val FORBIDDEN: Set<String> =
        setOf("finish", "update_plan", "note", "history_recall", "observe", "run_flow", "tap_node", "scroll_node")
}

/** 对外调用类节点：tool / llm / api / agent。 */
internal class ExternalNodes(
    private val env: NodeEnv,
) {
    @Suppress("ReturnCount") // 工具结果的每种情形（拦截、失败、未生效、成功）各自一个出口
    suspend fun tool(
        node: ToolNode,
        frame: Frame,
    ): Attempt {
        if (node.tool in FlowToolRules.FORBIDDEN) {
            return Attempt.Failed(FlowErrorCodes.TOOL_FORBIDDEN, "流程不能使用工具 ${node.tool}")
        }
        val args = toolArgs(node, frame)
        val access = env.ports.tools.accessOf(node.tool)
        val expect = env.verifier.expectFor(node, frame, access)
        while (true) {
            val before = expect?.let { env.verifier.fingerprint(frame) }
            val r = env.ports.tools.call(node.tool, args, node.id)
            if (access == null || access == ToolAccess.WRITE_SCREEN) env.screens.markDirty(action = true)
            val control = r.control
            if (control != null) {
                when (env.ports.control.pauseOnTool(control, node.id)) {
                    PauseResolution.RETRY_NODE -> continue
                    PauseResolution.SKIP_NODE -> return Attempt.Ok(skipped = true, input = args)
                    PauseResolution.END_RUN -> return Attempt.Ok(
                        Signal.Fail(FlowErrorCodes.CANCELLED, "用户结束了运行"),
                        input = args,
                    )
                }
            }
            if (!r.ok) {
                val detail = r.errorHint?.let { "${r.text}（$it）" } ?: r.text
                return Attempt.Failed(r.errorCode ?: FlowErrorCodes.ARGS_INVALID, detail, args)
            }
            if (expect != null && !env.verifier.await(expect, frame, before)) {
                // 结果契约（cond）不成立与"动作没效果"分开记错误码（§8）。
                val code = if (expect.cond != null) FlowErrorCodes.CONTRACT_UNMET else FlowErrorCodes.ACTION_NO_EFFECT
                val message = if (expect.cond != null) "结果契约不成立" else "动作已执行，但未出现预期效果：${expect.kind.name}"
                return Attempt.Failed(code, message, args)
            }
            // 结果契约缺失的写屏幕节点只能算"未验证"（§7.2），执行记录里留痕。
            if (expect == null && access == ToolAccess.WRITE_SCREEN) {
                env.ports.trace.log("info", "node=${node.id} contract=none verified=false")
            }
            val out =
                JsonObject(
                    mapOf(
                        "ok" to JsonPrimitive(true),
                        "text" to JsonPrimitive(r.text),
                        "data" to (r.data ?: JsonNull),
                    ),
                )
            frame.store(node.out, out)
            return Attempt.Ok(output = out, text = r.text, input = args)
        }
    }

    /** 渲染参数，并把 target / target2 解析成坐标（swipe 用 x1,y1,x2,y2，其余用 x,y）。 */
    private suspend fun toolArgs(
        node: ToolNode,
        frame: Frame,
    ): JsonObject {
        // 替代点击提示只作用于紧随其后的一次调用：读取后立即清空。
        val ratio = env.hints.pointRatio
        env.hints.pointRatio = null
        val args = LinkedHashMap(env.renderObject(frame, node.args))
        val swipe = node.tool == SWIPE
        node.target?.let {
            putPoint(args, frame, it, if (swipe) "x1" else "x", if (swipe) "y1" else "y", if (swipe) null else ratio)
        }
        node.target2?.let { putPoint(args, frame, it, "x2", "y2", null) }
        return JsonObject(args)
    }

    private suspend fun putPoint(
        args: MutableMap<String, JsonElement>,
        frame: Frame,
        target: Target,
        xKey: String,
        yKey: String,
        ratio: Pair<Double, Double>?,
    ) {
        if (args.containsKey(xKey) && args.containsKey(yKey)) return
        val hit =
            env.screens
                .require(frame, target)
                .hits
                .first()
        val bounds = hit.node?.bounds
        if (ratio != null && bounds != null) {
            args[xKey] = JsonPrimitive(bounds.l + (bounds.width * ratio.first).toInt())
            args[yKey] = JsonPrimitive(bounds.t + (bounds.height * ratio.second).toInt())
            return
        }
        args[xKey] = JsonPrimitive(hit.point.x)
        args[yKey] = JsonPrimitive(hit.point.y)
    }

    // ---- llm ----------------------------------------------------------------------------------

    suspend fun llm(
        node: LlmNode,
        frame: Frame,
    ): Attempt {
        val maxRetries = node.retry.max ?: env.llmCfg.retries
        val prompt = frame.templates.renderString(node.prompt)
        val system = node.system?.let { frame.templates.renderString(it) }
        val images = node.images.map { frame.templates.renderString(it) }
        val screenText = if (node.screen) env.screens.screen(null, frame.flow.settings.observeMode).rawText else null
        var feedback: String? = null
        var previous: String? = null
        for (attempt in 0..maxRetries) {
            checkBudget(frame)
            val reply = env.ports.llm.complete(buildCall(node, prompt, system, images, screenText, feedback, previous))
            env.tokens.add(reply)
            env.ports.trace.counters(RunCounters(llmCalls = 1, llmTokens = reply.promptTokens + reply.completionTokens))
            val problem =
                when (val parsed = LlmOutputParser.parse(reply.text, node.output)) {
                    is Parsed.Invalid -> parsed.reason
                    is Parsed.Value -> {
                        val bad = validate(frame, parsed.value, node)
                        if (bad == null) {
                            frame.store(node.out, parsed.value)
                            return Attempt.Ok(
                                output = parsed.value,
                                text = Values.str(parsed.value),
                                input = JsonPrimitive(prompt),
                            )
                        }
                        bad
                    }
                }
            previous = reply.text
            feedback = if (node.retry.feedback) problem else null
            if (attempt == maxRetries) return invalid(node, frame, problem, prompt)
        }
        return invalid(node, frame, "未知", prompt)
    }

    private fun invalid(
        node: LlmNode,
        frame: Frame,
        problem: String,
        prompt: String,
    ): Attempt {
        val fallback = node.onInvalid
        if (fallback != null) {
            val value = frame.eval(fallback.use)
            frame.store(node.out, value)
            return Attempt.Ok(output = value, text = Values.str(value), input = JsonPrimitive(prompt))
        }
        return Attempt.Failed(FlowErrorCodes.LLM_INVALID_OUTPUT, problem, JsonPrimitive(prompt))
    }

    private fun validate(
        frame: Frame,
        value: JsonElement,
        node: LlmNode,
    ): String? =
        LlmOutputParser.validate(value, node.validators) { expr, out ->
            val saved = frame.vars["out"]
            frame.vars["out"] = out
            try {
                Values.truthy(frame.eval(expr))
            } finally {
                if (saved == null) frame.vars.remove("out") else frame.vars["out"] = saved
            }
        }

    private fun checkBudget(frame: Frame) {
        val budget = frame.flow.settings.budget ?: return
        val maxCalls = budget.maxLlmCalls
        if (maxCalls != null && maxCalls > 0 && env.tokens.calls >= maxCalls) {
            throw FlowException(FlowErrorCodes.BUDGET_EXCEEDED, "LLM 调用次数超过预算 $maxCalls")
        }
        val maxTokens = budget.maxLlmTokens
        if (maxTokens != null && maxTokens > 0 && env.tokens.tokens >= maxTokens) {
            throw FlowException(FlowErrorCodes.BUDGET_EXCEEDED, "LLM token 超过预算 $maxTokens")
        }
    }

    private fun buildCall(
        node: LlmNode,
        prompt: String,
        system: String?,
        images: List<String>,
        screenText: String?,
        feedback: String?,
        previous: String?,
    ): LlmCall =
        LlmCall(
            nodeId = node.id,
            role = node.role,
            system = system,
            prompt = prompt,
            images = images,
            screenText = screenText,
            attachScreenshot = node.screen,
            jsonSchema = node.output.schema.takeIf { node.output.type == LlmOutputType.JSON },
            maxOutputTokens = node.limits.maxOutputTokens ?: env.llmCfg.maxOutputTokens,
            temperature = node.temperature,
            feedback = feedback,
            previousOutput = previous,
        )

    // ---- api ----------------------------------------------------------------------------------

    suspend fun api(
        node: ApiNode,
        frame: Frame,
    ): Attempt {
        val call =
            ApiCall(
                endpoint = frame.templates.renderString(node.endpoint),
                method = node.method.name,
                path = frame.templates.renderString(node.path),
                query = env.renderObject(frame, node.query).mapValues { Values.str(it.value) },
                headers = env.renderObject(frame, node.headers).mapValues { Values.str(it.value) },
                bodyType = node.bodyType.name.lowercase(),
                body = node.body?.let { frame.templates.renderJson(it) },
                timeoutMs = node.timeoutMs,
            )
        env.ports.trace.counters(RunCounters(apiCalls = 1))
        val reply = env.ports.api.call(call)
        val input =
            JsonObject(
                mapOf(
                    "endpoint" to JsonPrimitive(call.endpoint),
                    "method" to JsonPrimitive(call.method),
                    "path" to JsonPrimitive(call.path),
                ),
            )
        if (!statusOk(node, reply.status)) {
            return Attempt.Failed(FlowErrorCodes.API_STATUS, "接口返回状态 ${reply.status}", input)
        }
        val out =
            JsonObject(
                mapOf(
                    "status" to JsonPrimitive(reply.status),
                    "headers" to JsonObject(reply.headers.mapValues { JsonPrimitive(it.value) }),
                    "json" to (reply.json ?: JsonNull),
                    "text" to JsonPrimitive(reply.text),
                ),
            )
        frame.store(node.out, out)
        return Attempt.Ok(output = out, text = reply.text.take(TEXT_PREVIEW), input = input)
    }

    private fun statusOk(
        node: ApiNode,
        status: Int,
    ): Boolean {
        if (node.expectStatus.isEmpty()) return status in OK_RANGE
        return node.expectStatus.any { e ->
            when (e) {
                is JsonArray -> {
                    val lo = Values.numOrNull(e.getOrNull(0))?.toInt() ?: return@any false
                    val hi = Values.numOrNull(e.getOrNull(1))?.toInt() ?: lo
                    status in lo..hi
                }
                else -> Values.numOrNull(e)?.toInt() == status
            }
        }
    }

    // ---- agent --------------------------------------------------------------------------------

    suspend fun agent(
        node: AgentNode,
        frame: Frame,
    ): Attempt {
        val goal = frame.templates.renderString(node.goal)
        env.screens.markDirty(action = true)
        val r =
            env.ports.agent.run(
                node.id,
                goal,
                node.tools,
                node.maxSteps ?: env.agentCfg.maxSteps,
                node.maxDurationMs,
                node.role,
            )
        env.screens.markDirty(action = true)
        val input = JsonPrimitive(goal)
        if (!r.ok) return Attempt.Failed(r.errorCode ?: FlowErrorCodes.AGENT_FAILED, r.summary, input)
        val out =
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "summary" to JsonPrimitive(r.summary),
                    "outputs" to (r.outputs ?: JsonNull),
                ),
            )
        frame.store(node.out, out)
        return Attempt.Ok(output = out, text = r.summary, input = input)
    }

    private companion object {
        const val SWIPE = "swipe"
        const val TEXT_PREVIEW = 2000
        val OK_RANGE = 200..299
    }
}
