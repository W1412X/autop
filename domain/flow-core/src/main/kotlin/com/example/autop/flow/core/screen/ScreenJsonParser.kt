@file:Suppress("NestedBlockDepth", "SwallowedException", "ThrowsCount", "MagicNumber") // 观测 JSON 的容错解析；坐标数组长度为协议常量

package com.example.autop.flow.core.screen

import com.example.autop.device.ObserveMode
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenNode
import com.example.autop.device.ScreenQuality
import com.example.autop.device.ScreenWindow
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.core.expr.FlowException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 解析 `tool.observe` 的统一 JSON 输出（docs/design/observation-modes-coordinate-actions §3）
 * 回 [ScreenModel]。文本末尾的"建议换模式"行会被忽略；流程只使用完整观测。
 */
public object ScreenJsonParser {
    private const val ROOT = "screen"
    private const val ARRAY_RECT = 4
    private const val ARRAY_POINT = 2

    /** 解析失败抛 [FlowException]（`flow.invalid`）。 */
    public fun parse(text: String): ScreenModel {
        val body = firstJsonValue(text)
        val root =
            try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (e: IllegalArgumentException) {
                throw FlowException(FlowErrorCodes.INVALID, "观测输出不是合法 JSON：${e.message}")
            } ?: throw FlowException(FlowErrorCodes.INVALID, "观测输出不是 JSON 对象")
        val screen =
            root[ROOT] as? JsonObject
                ?: throw FlowException(FlowErrorCodes.INVALID, "观测输出缺少 screen（可能是差量观测）")
        val mode = ObserveMode.parse(screen.str("mode")) ?: ObserveMode.A11Y
        val windows = (screen["windows"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::window) }
        val q = screen["quality"] as? JsonObject
        val quality =
            ScreenQuality(
                nodes = q?.int("nodes") ?: windows.sumOf { countNodes(it.kids) },
                interactive = q?.int("interactive") ?: 0,
                degenerate = q?.bool("degenerate") ?: false,
                poor = q?.bool("poor") ?: false,
                uncertain = q?.int("uncertain"),
                suggest = ObserveMode.parse(q?.str("suggest")),
            )
        return ScreenModel(mode, windows, quality, cached = screen.bool("cached") ?: false)
    }

    /** 从文本中截取第一个完整 JSON 值（括号配平，识别字符串转义）。 */
    private fun firstJsonValue(text: String): String {
        val start = text.indexOf('{')
        if (start < 0) throw FlowException(FlowErrorCodes.INVALID, "观测输出里没有 JSON")
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        throw FlowException(FlowErrorCodes.INVALID, "观测 JSON 不完整")
    }

    private fun countNodes(nodes: List<ScreenNode>): Int = nodes.sumOf { 1 + countNodes(it.kids) }

    private fun window(o: JsonObject): ScreenWindow =
        ScreenWindow(
            app = o.str("app").orEmpty(),
            page = o.str("page"),
            type = o.str("type"),
            active = o.bool("active") ?: false,
            focus = rect(o["focus"]),
            collapsed = o.bool("collapsed") ?: false,
            redacted = o.bool("redacted") ?: false,
            kids = (o["kids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::node) },
        )

    private fun node(o: JsonObject): ScreenNode =
        ScreenNode(
            tag = o.str("tag").orEmpty(),
            text = o.str("text"),
            icon = o.str("icon"),
            desc = o.str("desc"),
            hint = o.str("hint"),
            state = o.str("state"),
            cls = o.str("cls"),
            vid = o.str("vid"),
            act = strings(o["act"]),
            st = strings(o["st"]),
            bounds = rect(o["bounds"]) ?: Rect(0, 0, 0, 0),
            at = point(o["at"]),
            more = o.str("more"),
            kids = (o["kids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::node) },
        )

    private fun strings(e: JsonElement?): List<String> =
        (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun ints(
        e: JsonElement?,
        size: Int,
    ): List<Int>? {
        val list = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: return null
        return list.takeIf { it.size == size }
    }

    private fun rect(e: JsonElement?): Rect? = ints(e, ARRAY_RECT)?.let { Rect(it[0], it[1], it[2], it[3]) }

    private fun point(e: JsonElement?): Point? = ints(e, ARRAY_POINT)?.let { Point(it[0], it[1]) }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
