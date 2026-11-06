package com.example.autop.flow.core

import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.core.expr.ExprEnv
import com.example.autop.flow.core.expr.ExprEvaluator
import com.example.autop.flow.core.expr.FlowException
import com.example.autop.flow.core.expr.TemplateRenderer
import com.example.autop.flow.core.expr.Values
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId

/** 运行元信息，对应表达式命名空间 `run`。 */
internal data class RunMeta(
    val runId: String,
    val startedAt: Long,
    val origin: String,
    val flowName: String,
    val taskId: String?,
)

/**
 * 一次流程（含子流程）调用的变量作用域（§2.4）：运行级全局变量 + 内置命名空间
 * `param` / `trigger` / `run` / `last`。子流程各有独立 Frame。
 */
internal class Frame(
    val flow: FlowDefinition,
    val params: JsonObject,
    private val trigger: JsonObject,
    private val meta: RunMeta,
    private val clock: FlowClock,
    exprMaxSteps: Int,
    val depth: Int,
    private val maxVarBytes: Int,
) : ExprEnv {
    val vars: MutableMap<String, JsonElement> = HashMap()

    /** 上一个节点的结果 `{ok, error, text}`。 */
    var last: JsonObject =
        JsonObject(
            mapOf(
                "ok" to JsonPrimitive(true),
                "error" to JsonNull,
                "text" to JsonPrimitive(""),
            ),
        )

    private val runObject: JsonObject =
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(meta.runId),
                "startedAt" to JsonPrimitive(meta.startedAt),
                "origin" to JsonPrimitive(meta.origin),
                "flowName" to JsonPrimitive(meta.flowName),
                "taskId" to (meta.taskId?.let { JsonPrimitive(it) } ?: JsonNull),
            ),
        )

    private val evaluator = ExprEvaluator(this, exprMaxSteps)
    val templates: TemplateRenderer = TemplateRenderer(evaluator)

    override val zone: ZoneId
        get() = clock.zone

    override fun lookup(name: String): JsonElement? =
        when (name) {
            "param" -> params
            "trigger" -> trigger
            "run" -> runObject
            "last" -> last
            else -> vars[name]
        }

    override fun nowMs(): Long = clock.nowMs()

    override fun random(
        lo: Double,
        hi: Double,
    ): Double = clock.random(lo, hi)

    fun eval(expr: String): JsonElement = evaluator.eval(expr)

    /** 写入变量并检查大小上限（`flow.var_too_large`）。 */
    fun assign(
        name: String,
        value: JsonElement,
    ) {
        if (name in RESERVED) throw FlowException(FlowErrorCodes.INVALID, "变量名 $name 是保留名")
        if (maxVarBytes > 0 && Values.sizeBytes(value) > maxVarBytes) {
            throw FlowException(FlowErrorCodes.VAR_TOO_LARGE, "变量 $name 超过 $maxVarBytes 字节上限")
        }
        vars[name] = value
    }

    private companion object {
        val RESERVED = setOf("param", "trigger", "run", "last")
    }
}
