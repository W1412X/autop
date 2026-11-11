/**
 * 统一 JSON 渲染器（docs/design/observation-modes-coordinate-actions §3）：
 * `ScreenModel` → 合法 JSON；每个节点对象独占一行，不加缩进。
 */
package com.example.autop.device.core

import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenNode
import com.example.autop.device.ScreenQuality
import com.example.autop.device.ScreenWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 统一 JSON 渲染器。 */
public object ScreenJsonRenderer {
    /** 完整观测文本（含 quality 与可选建议尾行）。 */
    public fun render(
        model: ScreenModel,
        coordText: String,
        suggestion: String?,
    ): String {
        val body = renderBody(model, coordText)
        return if (suggestion.isNullOrBlank()) body else body + "\n" + suggestion
    }

    private fun renderBody(
        model: ScreenModel,
        coordText: String,
    ): String {
        val head =
            buildJsonObject {
                put(ScreenJsonWire.FIELD_VERSION, VERSION)
                put(ScreenJsonWire.FIELD_MODE, model.mode.id)
                put(ScreenJsonWire.FIELD_UNTRUSTED, true)
                put(ScreenJsonWire.FIELD_COORD, coordText)
                put(ScreenJsonWire.FIELD_FIELDS, jsonArray(ScreenJsonWire.fieldsOf(model.mode)))
            }.toString()
        val windows = model.windows.joinToString(",\n") { windowLine(it) }
        val quality = renderQuality(model.quality)
        val cached = if (model.cached) ",\"${ScreenJsonWire.FIELD_CACHED}\":true" else ""
        return buildString {
            append("{\"${ScreenJsonWire.ROOT_SCREEN}\":")
            append(head.removeSuffix("}"))
            append(",\n\"${ScreenJsonWire.FIELD_WINDOWS}\":[\n")
            append(windows)
            append("\n],\n\"${ScreenJsonWire.FIELD_QUALITY}\":")
            append(quality)
            append(cached)
            append("}}")
        }
    }

    /** 质量对象：false/null 值按 §3.1 省略。 */
    private fun renderQuality(quality: ScreenQuality): String =
        buildJsonObject {
            put(ScreenJsonWire.FIELD_NODES, quality.nodes)
            put(ScreenJsonWire.FIELD_INTERACTIVE, quality.interactive)
            if (quality.degenerate) put(ScreenJsonWire.FIELD_DEGENERATE, true)
            if (quality.poor) put(ScreenJsonWire.FIELD_POOR, true)
            quality.insufficient?.let { put(ScreenJsonWire.FIELD_INSUFFICIENT, it) }
            quality.suggest?.let { put(ScreenJsonWire.FIELD_SUGGEST, it.id) }
            if (quality.truncated) put(ScreenJsonWire.FIELD_TRUNCATED, true)
        }.toString()

    private fun windowLine(window: ScreenWindow): String {
        val head =
            buildJsonObject {
                put(ScreenJsonWire.FIELD_APP, window.app)
                window.page?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_PAGE, it) }
                window.type?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_TYPE, it) }
                if (window.active) put(ScreenJsonWire.FIELD_ACTIVE, true)
                window.focus?.let { put(ScreenJsonWire.FIELD_FOCUS, rectArray(it)) }
                if (window.collapsed) put(ScreenJsonWire.FIELD_COLLAPSED, true)
                if (window.redacted) put(ScreenJsonWire.FIELD_REDACTED, true)
            }.toString()
        return withKids(head, window.kids)
    }

    private fun withKids(
        head: String,
        kids: List<ScreenNode>,
    ): String {
        val open = head.removeSuffix("}")
        if (kids.isEmpty()) return "$open}"
        return open + ",\"${ScreenJsonWire.FIELD_KIDS}\":[\n" +
            kids.joinToString(",\n") { nodeLine(it) } +
            "\n]}"
    }

    /** 一个节点对象独占一行（含嵌套时子节点各自一行）。 */
    private fun nodeLine(node: ScreenNode): String {
        val head = renderCompact(nodeObject(node, includeKids = false))
        return withKids(head, node.kids)
    }

    /** 节点 JSON 对象（[includeKids]=false 用于差量与嵌套头部）。 */
    private fun nodeObject(
        node: ScreenNode,
        includeKids: Boolean,
    ): JsonObject =
        buildJsonObject {
            put(ScreenJsonWire.FIELD_TAG, node.tag)
            node.id?.let { put(ScreenJsonWire.FIELD_ID, it) }
            putTextFields(node)
            if (node.act.isNotEmpty()) put(ScreenJsonWire.FIELD_ACT, jsonArray(node.act))
            if (node.st.isNotEmpty()) put(ScreenJsonWire.FIELD_ST, jsonArray(node.st))
            put(ScreenJsonWire.FIELD_BOUNDS, rectArray(node.bounds))
            node.at?.let { put(ScreenJsonWire.FIELD_AT, pointArray(it)) }
            node.more?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_MORE, it) }
            if (includeKids && node.kids.isNotEmpty()) {
                put(ScreenJsonWire.FIELD_KIDS, JsonArray(node.kids.map { nodeObject(it, includeKids = true) }))
            }
        }

    /** 节点的文字类可选字段（空值不输出）。 */
    private fun JsonObjectBuilder.putTextFields(node: ScreenNode) {
        node.text?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_TEXT, it) }
        node.icon?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_ICON, it) }
        node.desc?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_DESC, it) }
        node.hint?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_HINT, it) }
        node.state?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_STATE, it) }
        node.cls?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_CLS, it) }
        node.vid?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_VID, it) }
    }

    /** 差量观测（§3.4）：`added`/`changed` 为完整节点，`removed` 只留 tag + 文字 + bounds。 */
    public fun renderDiff(
        mode: com.example.autop.device.ObserveMode,
        added: List<ScreenNode>,
        changed: List<ScreenNode>,
        removed: List<ScreenNode>,
    ): String {
        val head =
            buildJsonObject {
                put(ScreenJsonWire.FIELD_VERSION, VERSION)
                put(ScreenJsonWire.FIELD_MODE, mode.id)
            }.toString()
        val sections =
            listOf(
                ScreenJsonWire.FIELD_ADDED to added.map { diffNode(it) },
                ScreenJsonWire.FIELD_CHANGED to changed.map { diffNode(it) },
                ScreenJsonWire.FIELD_REMOVED to removed.map { removedNode(it) },
            ).filter { it.second.isNotEmpty() }
        val body =
            sections.joinToString("") { (name, lines) ->
                ",\n\"$name\":[\n" + lines.joinToString(",\n") + "\n]"
            }
        return "{\"${ScreenJsonWire.ROOT_DIFF}\":" + head.removeSuffix("}") + body + "}}"
    }

    /** 差量节点（无 kids，供 added/changed 使用）。 */
    public fun diffNode(node: ScreenNode): String = renderCompact(nodeObject(node, includeKids = false))

    /** 消失节点（tag + 第一个非空文字字段 + bounds）。 */
    public fun removedNode(node: ScreenNode): String {
        val obj =
            buildJsonObject {
                put(ScreenJsonWire.FIELD_TAG, node.tag)
                firstTextOf(node)?.let { (key, value) -> put(key, value) }
                put(ScreenJsonWire.FIELD_BOUNDS, rectArray(node.bounds))
            }
        return renderCompact(obj)
    }

    private fun firstTextOf(node: ScreenNode): Pair<String, String>? =
        when {
            !node.text.isNullOrEmpty() -> ScreenJsonWire.FIELD_TEXT to node.text.orEmpty()
            !node.icon.isNullOrEmpty() -> ScreenJsonWire.FIELD_ICON to node.icon.orEmpty()
            !node.desc.isNullOrEmpty() -> ScreenJsonWire.FIELD_DESC to node.desc.orEmpty()
            else -> null
        }

    private fun renderCompact(element: JsonElement): String = Json.encodeToString(JsonElement.serializer(), element)

    internal fun rectArray(rect: Rect): JsonArray =
        JsonArray(listOf(rect.l, rect.t, rect.r, rect.b).map { JsonPrimitive(it) })

    internal fun pointArray(point: Point): JsonArray = JsonArray(listOf(JsonPrimitive(point.x), JsonPrimitive(point.y)))

    private fun jsonArray(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    /** 格式版本。 */
    public const val VERSION: Int = 1
}
