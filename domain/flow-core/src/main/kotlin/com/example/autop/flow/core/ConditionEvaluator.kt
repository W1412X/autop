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
import com.example.autop.flow.core.expr.Values
import com.example.autop.flow.core.screen.TargetMatch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalTime
import java.time.ZonedDateTime

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
    ): Boolean =
        when (cond) {
            is Condition.Expr -> Values.truthy(frame.eval(cond.expr))
            is Condition.ScreenText -> screenText(cond, frame)
            is Condition.Element -> element(cond, frame)
            is Condition.App -> app(cond, frame)
            is Condition.ScreenChanged -> {
                screens.screen(null, frame.flow.settings.observeMode, force = true)
                screens.changedSince(cond.since)
            }
            is Condition.Ime -> ime(cond, frame)
            is Condition.Device -> device(cond)
            is Condition.TimeWindow -> timeWindow(cond, frame)
            is Condition.LastOk -> lastOk[cond.node] ?: false
            is Condition.Llm -> llm(cond, frame)
            is Condition.All -> cond.of.all { eval(it, frame) }
            is Condition.Any -> cond.of.any { eval(it, frame) }
            is Condition.Not -> !eval(cond.of, frame)
        }

    private suspend fun screenText(
        cond: Condition.ScreenText,
        frame: Frame,
    ): Boolean {
        val screen = screens.screen(cond.mode, frame.flow.settings.observeMode).screen
        val value = frame.templates.renderString(cond.value)
        val text = screens.pageText(screen)
        return when (cond.op) {
            TextOp.CONTAINS -> text.contains(value, ignoreCase = true)
            TextOp.NOT_CONTAINS -> !text.contains(value, ignoreCase = true)
            TextOp.EQUALS -> text.lines().any { it.trim() == value.trim() }
            TextOp.REGEX -> runCatching { Regex(value).containsMatchIn(text) }.getOrDefault(false)
        }
    }

    private suspend fun element(
        cond: Condition.Element,
        frame: Frame,
    ): Boolean {
        val match = screens.find(frame, cond.target, wait = false)
        val node = (match as? TargetMatch.Found)?.hits?.firstOrNull()?.node
        return when (cond.op) {
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
    }

    private suspend fun app(
        cond: Condition.App,
        frame: Frame,
    ): Boolean {
        val value = frame.templates.renderString(cond.value)
        val fg = ports.device.state().foregroundPackage
        val screen = screens.screen(null, frame.flow.settings.observeMode).screen
        val appMatches =
            fg == value || screen.windows.any { it.active && it.app == value } || screen.windows.any { it.app == value }
        val page = cond.page?.let { frame.templates.renderString(it) } ?: return appMatches
        return appMatches && screen.windows.any { it.page?.contains(page) == true }
    }

    private suspend fun ime(
        cond: Condition.Ime,
        frame: Frame,
    ): Boolean {
        val screen = screens.screen(null, frame.flow.settings.observeMode).screen
        val shown = screen.windows.any { it.type?.contains("ime", ignoreCase = true) == true }
        return shown == cond.shown
    }

    private fun device(cond: Condition.Device): Boolean {
        val state: DeviceState = ports.device.state()
        val actual: JsonElement =
            when (cond.metric) {
                DeviceMetric.BATTERY -> state.batteryPercent?.let { JsonPrimitive(it) } ?: return false
                DeviceMetric.CHARGING -> JsonPrimitive(state.charging)
                DeviceMetric.NETWORK -> JsonPrimitive(state.networkType)
                DeviceMetric.SCREEN_ON -> JsonPrimitive(state.screenOn)
                DeviceMetric.LOCKED -> JsonPrimitive(state.keyguardLocked)
            }
        return compare(actual, cond.op, cond.value)
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

    private fun timeWindow(
        cond: Condition.TimeWindow,
        frame: Frame,
    ): Boolean {
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ports.clock.nowMs()), frame.zone)
        cond.weekdays?.let { if (now.dayOfWeek.value !in it) return false }
        val range = cond.between ?: return true
        if (range.size != 2) return true
        val start = LocalTime.parse(range[0])
        val end = LocalTime.parse(range[1])
        val t = now.toLocalTime()
        return if (start <= end) t >= start && t <= end else t >= start || t <= end
    }

    private suspend fun llm(
        cond: Condition.Llm,
        frame: Frame,
    ): Boolean {
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
        return (parsed as? Parsed.Value)?.let { Values.truthy(it.value) } ?: false
    }
}
