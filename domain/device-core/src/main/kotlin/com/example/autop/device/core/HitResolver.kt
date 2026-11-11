/**
 * 命中回执（docs/design/observation-modes-coordinate-actions §5.4）：
 * 落点 → 包含它的最小可操作节点；没有可操作节点时取包含它的最小节点。
 */
package com.example.autop.device.core

import com.example.autop.device.ObserveMode
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenNode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 命中节点解析与 JSON 回执。 */
public object HitResolver {
    /** 包含 [point] 的最小可操作节点；没有则最小节点；都没有 null。 */
    public fun resolve(
        point: Point,
        model: ScreenModel,
    ): ScreenNode? {
        val hits =
            model.windows
                .flatMap { it.kids }
                .flatMap { flatten(it) }
                .filter { contains(it.bounds, point) }
        return hits.filter { it.act.isNotEmpty() }.minByOrNull { it.bounds.area }
            ?: hits.minByOrNull { it.bounds.area }
    }

    /**
     * 命中回执 JSON 一行：`{"hit":{"at":[…],"tag":…,"text"…,"bounds":[…],"from":"a11y"}}`；
     * 没有命中树（[model] 为 null）或落点不在任何节点内时输出 `{"hit":{"at":[…],"none":true}}`。
     */
    public fun receipt(
        point: Point,
        model: ScreenModel?,
        from: ObserveMode?,
    ): String {
        val node = model?.let { resolve(point, it) }
        val hit =
            if (node == null) {
                buildJsonObject {
                    put(ScreenJsonWire.FIELD_AT, ScreenJsonRenderer.pointArray(point))
                    put(ScreenJsonWire.FIELD_NONE, true)
                }
            } else {
                buildJsonObject {
                    put(ScreenJsonWire.FIELD_AT, ScreenJsonRenderer.pointArray(point))
                    put(ScreenJsonWire.FIELD_TAG, node.tag)
                    node.text?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_TEXT, it) }
                    node.icon?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_ICON, it) }
                    node.desc?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_DESC, it) }
                    put(ScreenJsonWire.FIELD_BOUNDS, ScreenJsonRenderer.rectArray(node.bounds))
                    from?.let { put(ScreenJsonWire.FIELD_FROM, it.id) }
                }
            }
        return buildJsonObject { put(ScreenJsonWire.ROOT_HIT, hit) }.toString()
    }

    /** 命中节点描述（回放 `_hit`）：`tag`、文字/图标/描述与 `bounds`；未命中返回 null。 */
    public fun describe(
        point: Point,
        model: ScreenModel?,
    ): kotlinx.serialization.json.JsonObject? {
        val node = model?.let { resolve(point, it) } ?: return null
        return buildJsonObject {
            put(ScreenJsonWire.FIELD_TAG, node.tag)
            node.text?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_TEXT, it) }
            node.icon?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_ICON, it) }
            node.desc?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_DESC, it) }
            put(ScreenJsonWire.FIELD_BOUNDS, ScreenJsonRenderer.rectArray(node.bounds))
        }
    }

    /** 节点回执 JSON 一行（节点工具结果用）：`{"node":{"id":12,"tag":…,"text":…,"bounds":[…],"at":[…]}}`。 */
    public fun nodeReceipt(node: ScreenNode): String {
        val obj =
            buildJsonObject {
                node.id?.let { put(ScreenJsonWire.FIELD_ID, it) }
                put(ScreenJsonWire.FIELD_TAG, node.tag)
                node.text?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_TEXT, it) }
                node.icon?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_ICON, it) }
                node.desc?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_DESC, it) }
                put(ScreenJsonWire.FIELD_BOUNDS, ScreenJsonRenderer.rectArray(node.bounds))
                node.at?.let { put(ScreenJsonWire.FIELD_AT, ScreenJsonRenderer.pointArray(it)) }
            }
        return buildJsonObject { put(ScreenJsonWire.ROOT_NODE, obj) }.toString()
    }

    /** 节点描述（回放 `_hit` 用，与 [describe] 同形）：`tag`、文字/图标/描述与 `bounds`。 */
    public fun describeNode(node: ScreenNode): kotlinx.serialization.json.JsonObject =
        buildJsonObject {
            put(ScreenJsonWire.FIELD_TAG, node.tag)
            node.text?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_TEXT, it) }
            node.icon?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_ICON, it) }
            node.desc?.takeIf { it.isNotEmpty() }?.let { put(ScreenJsonWire.FIELD_DESC, it) }
            put(ScreenJsonWire.FIELD_BOUNDS, ScreenJsonRenderer.rectArray(node.bounds))
        }

    private fun flatten(node: ScreenNode): List<ScreenNode> = listOf(node) + node.kids.flatMap { flatten(it) }

    private fun contains(
        bounds: Rect,
        point: Point,
    ): Boolean = point.x in bounds.l..bounds.r && point.y in bounds.t..bounds.b
}
