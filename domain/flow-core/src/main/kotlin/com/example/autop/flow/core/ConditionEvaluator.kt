@file:Suppress("CyclomaticComplexMethod") // 条件种类的分发表：分支数等于条件种类数

package com.example.autop.flow.core

import com.example.autop.device.DeviceState
import com.example.autop.flow.CompareOp
import com.example.autop.flow.Condition
import com.example.autop.flow.DeviceMetric
import com.example.autop.flow.ElementOp
import com.example.autop.flow.LlmOutput
import com.example.autop.flow.LlmOutputType
import com.example.autop.flow.TextOp
import com.example.autop.flow.core.expr.FlowException
import com.example.autop.flow.core.expr.Values
import com.example.autop.flow.core.screen.TargetMatch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 单个条件的求值明细（docs/design/flows-precondition-selfrepair.md P3）。
 *
 * `error` 非空表示条件本身写错（非法正则、模板缺变量等），与"条件不成立"区分：
 * 调用方据 `error` 走快速失败而不是重试。`actual` 含屏幕原文时由调用方负责脱敏与信封包装。
 */
internal data class CondDetail(
    /** 条件的可读描述，如「页面文字 contains 「发布成功」」。 */
    val label: String,
    /** 条件是否成立；求值出错时为 false。 */
    val ok: Boolean,
    /** 实际值摘要；可空。 */
    val actual: String? = null,
    /** 求值出错时的错误码与说明；为 null 表示正常求值。 */
    val error: String? = null,
    /** 子条件明细；复合条件非空。 */
    val children: List<CondDetail> = emptyList(),
)

/** 条件求值（docs/design/flows.md §4）。 */
internal class ConditionEvaluator(
    private val ports: FlowPorts,
    private val screens: ScreenService,
    private val lastOk: Map<String, Boolean>,
    private val llmCounter: suspend (LlmReply) -> Unit,
) {
    suspend fun eval(
        cond: Condition,
        frame: Frame,
    ): Boolean = detail(cond, frame).ok

    /**
     * 带明细的求值。`error` 非空表示条件本身写错（非法正则、模板缺变量等），
     * 与"条件不成立"区分：调用方据 `error` 走快速失败而不是重试。
     */
    suspend fun detail(
        cond: Condition,
        frame: Frame,
    ): CondDetail =
        when (cond) {
            is Condition.Expr -> exprDetail(cond, frame)
            is Condition.ScreenText -> screenTextDetail(cond, frame)
            is Condition.Element -> elementDetail(cond, frame)
            is Condition.App -> appDetail(cond, frame)
            is Condition.ScreenChanged -> screenChangedDetail(cond, frame)
            is Condition.Ime -> imeDetail(cond, frame)
            is Condition.Device -> deviceDetail(cond)
            is Condition.TimeWindow -> timeDetail(cond, frame)
            is Condition.LastOk -> lastOkDetail(cond)
            is Condition.Llm -> llmDetail(cond, frame)
            is Condition.All -> {
                val children = cond.of.map { detail(it, frame) }
                CondDetail("全部满足", children.all { it.ok }, children = children)
            }
            is Condition.Any -> {
                val children = cond.of.map { detail(it, frame) }
                CondDetail("任一满足", children.any { it.ok }, children = children)
            }
            is Condition.Not -> {
                val inner = detail(cond.of, frame)
                CondDetail("取反", !inner.ok, children = listOf(inner))
            }
        }

    private fun exprDetail(
        cond: Condition.Expr,
        frame: Frame,
    ): CondDetail =
        try {
            val value = frame.eval(cond.expr)
            CondDetail("表达式「${cond.expr}」为真", Values.truthy(value), actual = Values.str(value))
        } catch (e: FlowException) {
            CondDetail("表达式「${cond.expr}」求值出错", false, error = "${e.code}: ${e.message.orEmpty()}")
        }

    private suspend fun screenTextDetail(
        cond: Condition.ScreenText,
        frame: Frame,
    ): CondDetail {
        val label = "页面文字 ${cond.op.name.lowercase()} 「${cond.value}」"
        val value =
            try {
                frame.templates.renderString(cond.value)
            } catch (e: FlowException) {
                return CondDetail(label, false, error = "${e.code}: ${e.message.orEmpty()}")
            }
        val screen = screens.screen(cond.mode, frame.flow.settings.observeMode).screen
        val text = screens.pageText(screen)
        val ok =
            when (cond.op) {
                TextOp.CONTAINS -> text.contains(value, ignoreCase = true)
                TextOp.NOT_CONTAINS -> !text.contains(value, ignoreCase = true)
                TextOp.EQUALS -> text.lines().any { it.trim() == value.trim() }
                TextOp.REGEX ->
                    try {
                        Regex(value).containsMatchIn(text)
                    } catch (e: Exception) {
                        return CondDetail(label, false, error = "flow.expr_error: 非法正则「$value」")
                    }
            }
        return CondDetail(label, ok, actual = excerpt(text))
    }

    private suspend fun elementDetail(
        cond: Condition.Element,
        frame: Frame,
    ): CondDetail {
        val label = "元素 ${cond.op.name.lowercase()}（目标 ${cond.target.value ?: cond.target.tag ?: "区域"}）"
        val match = screens.find(frame, cond.target, wait = false)
        val node = (match as? TargetMatch.Found)?.hits?.firstOrNull()?.node
        val ok =
            when (cond.op) {
                ElementOp.EXISTS -> match is TargetMatch.Found
                ElementOp.ABSENT -> match is TargetMatch.NotFound
                ElementOp.CHECKED -> node?.st?.contains("checked") == true
                ElementOp.UNCHECKED -> node?.st?.contains("unchecked") == true
                ElementOp.ENABLED -> node != null && "disabled" !in node.st
                ElementOp.DISABLED -> node?.st?.contains("disabled") == true
                ElementOp.FOCUSED -> {
                    val focus =
                        screens
                            .screen(
                                null,
                                frame.flow.settings.observeMode,
                            ).screen.windows
                            .firstNotNullOfOrNull { it.focus }
                    node != null && focus != null && node.bounds == focus
                }
            }
        val actual = if (node == null) "未命中" else "命中 ${node.text ?: node.desc ?: node.cls ?: "元素"}"
        return CondDetail(label, ok, actual = actual)
    }

    private suspend fun appDetail(
        cond: Condition.App,
        frame: Frame,
    ): CondDetail {
        val label = if (cond.page == null) "前台应用为「${cond.value}」" else "前台应用为「${cond.value}」且页面含「${cond.page}」"
        val value =
            try {
                frame.templates.renderString(cond.value)
            } catch (e: FlowException) {
                return CondDetail(label, false, error = "${e.code}: ${e.message.orEmpty()}")
            }
        val fg = ports.device.state().foregroundPackage
        val screen = screens.screen(null, frame.flow.settings.observeMode).screen
        val appMatches =
            fg == value || screen.windows.any { it.active && it.app == value } || screen.windows.any { it.app == value }
        val page = cond.page?.let { frame.templates.renderString(it) }
        val ok = if (page == null) appMatches else appMatches && screen.windows.any { it.page?.contains(page) == true }
        return CondDetail(label, ok, actual = "前台 $fg")
    }

    private suspend fun screenChangedDetail(
        cond: Condition.ScreenChanged,
        frame: Frame,
    ): CondDetail {
        screens.screen(null, frame.flow.settings.observeMode, force = true)
        val changed = screens.changedSince(cond.since)
        return CondDetail("屏幕相对 ${cond.since} 已变化", changed, actual = if (changed) "已变化" else "未变化")
    }

    private suspend fun imeDetail(
        cond: Condition.Ime,
        frame: Frame,
    ): CondDetail {
        val screen = screens.screen(null, frame.flow.settings.observeMode).screen
        val shown = screen.windows.any { it.type?.contains("ime", ignoreCase = true) == true }
        val label = if (cond.shown) "输入法已显示" else "输入法已隐藏"
        return CondDetail(label, shown == cond.shown, actual = if (shown) "已显示" else "已隐藏")
    }

    private fun deviceDetail(cond: Condition.Device): CondDetail {
        val label = "设备 ${cond.metric.name.lowercase()} ${cond.op.name.lowercase()} ${cond.value}"
        val state: DeviceState = ports.device.state()
        val actual: JsonElement =
            when (cond.metric) {
                DeviceMetric.BATTERY ->
                    state.batteryPercent?.let { JsonPrimitive(it) }
                        ?: return CondDetail(label, false, actual = "不可用")
                DeviceMetric.CHARGING -> JsonPrimitive(state.charging)
                DeviceMetric.NETWORK -> JsonPrimitive(state.networkType)
                DeviceMetric.SCREEN_ON -> JsonPrimitive(state.screenOn)
                DeviceMetric.LOCKED -> JsonPrimitive(state.keyguardLocked)
            }
        return CondDetail(label, compare(actual, cond.op, cond.value), actual = Values.str(actual))
    }

    private fun compare(
        a: JsonElement,
        op: CompareOp,
        b: JsonElement,
    ): Boolean =
        when (op) {
            CompareOp.EQ -> Values.equal(a, b)
            CompareOp.NE -> !Values.equal(a, b)
            CompareOp.LT -> Values.compare(a, b) < 0
            CompareOp.LE -> Values.compare(a, b) <= 0
            CompareOp.GT -> Values.compare(a, b) > 0
            CompareOp.GE -> Values.compare(a, b) >= 0
        }

    private fun timeDetail(
        cond: Condition.TimeWindow,
        frame: Frame,
    ): CondDetail {
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ports.clock.nowMs()), frame.zone)
        val weekdays = cond.weekdays
        val between = cond.between
        val ok =
            when {
                weekdays != null && now.dayOfWeek.value !in weekdays -> false
                between == null -> true
                between.size != 2 -> true
                else -> {
                    val start = LocalTime.parse(between[0])
                    val end = LocalTime.parse(between[1])
                    val t = now.toLocalTime()
                    if (start <= end) t >= start && t <= end else t >= start || t <= end
                }
            }
        return CondDetail("当前时间在 ${between ?: "全时段"}", ok, actual = now.toLocalTime().toString())
    }

    private fun lastOkDetail(cond: Condition.LastOk): CondDetail {
        val ok = lastOk[cond.node] ?: false
        return CondDetail("节点 ${cond.node} 最近一次成功", ok, actual = if (ok) "成功" else "未成功")
    }

    private suspend fun llmDetail(
        cond: Condition.Llm,
        frame: Frame,
    ): CondDetail {
        val label = "模型判断：「${cond.prompt.take(PROMPT_LABEL_MAX)}」为真"
        val screenText = if (cond.screen) screens.screen(null, frame.flow.settings.observeMode).rawText else null
        val reply =
            ports.llm.complete(
                LlmCall(
                    nodeId = "cond",
                    role = cond.role,
                    system = null,
                    prompt = frame.templates.renderString(cond.prompt),
                    images = cond.images.map { frame.templates.renderString(it) },
                    screenText = screenText,
                    attachScreenshot = false,
                    jsonSchema = null,
                    maxOutputTokens = null,
                    temperature = null,
                ),
            )
        llmCounter(reply)
        val parsed = LlmOutputParser.parse(reply.text, LlmOutput(type = LlmOutputType.BOOL))
        val ok = (parsed as? Parsed.Value)?.let { Values.truthy(it.value) } ?: false
        return CondDetail(label, ok, actual = excerpt(reply.text))
    }

    /** 页面文字摘要：只保留前若干字符，避免长文本灌进上下文。 */
    private fun excerpt(text: String): String = text.take(EXCERPT_MAX)

    private companion object {
        /** 明细里文本摘要的字符上限。 */
        const val EXCERPT_MAX: Int = 200

        /** LLM 条件的提示词在明细里保留的字符上限。 */
        const val PROMPT_LABEL_MAX: Int = 60
    }
}
