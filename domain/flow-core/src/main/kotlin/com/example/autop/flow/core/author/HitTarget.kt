/**
 * 从回放动作的命中回执构造目标（`TrajectoryConverter` 与 `NodeRevision` 共用）。
 *
 * 顺序：文字 > 描述 > 图标；都没有时只保留坐标后备。用户永远看不到坐标。
 */
package com.example.autop.flow.core.author

import com.example.autop.flow.MatchMode
import com.example.autop.flow.Target
import com.example.autop.flow.TargetBy
import com.example.autop.flow.TargetFallback
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object HitTarget {
    /** 回放专用键，不能写进流程参数。 */
    private val INTERNAL_KEYS = setOf("_hit", "_locator")

    /** tap 的坐标键（由目标定位替代，不写进参数）。 */
    private val COORD_KEYS = setOf("x", "y")

    /** 由命中回执生成目标：文字 > 描述 > 图标；都没有时只保留坐标后备。 */
    fun targetOf(args: JsonObject): Target {
        val hit = hitOf(args)
        val text = hit?.str("text")
        val desc = hit?.str("desc")
        val icon = hit?.str("icon")
        val point = point(args)
        val fallback = point?.let { TargetFallback(at = listOf(it.first, it.second)) }
        return when {
            text != null -> Target(by = TargetBy.TEXT, value = text, match = MatchMode.EQUALS, fallback = fallback)
            desc != null -> Target(by = TargetBy.DESC, value = desc, match = MatchMode.EQUALS, fallback = fallback)
            icon != null -> Target(by = TargetBy.ICON, value = icon, match = MatchMode.EQUALS, fallback = fallback)
            else -> Target(by = TargetBy.TEXT, value = null, fallback = fallback)
        }
    }

    /** 去掉回放专用键与坐标键后的参数。 */
    fun publicArgs(
        args: JsonObject,
        dropCoords: Boolean,
    ): JsonObject {
        val keys = if (dropCoords) INTERNAL_KEYS + COORD_KEYS else INTERNAL_KEYS
        return JsonObject(args.filterKeys { it !in keys })
    }

    fun hitOf(args: JsonObject): JsonObject? {
        val raw: JsonElement = args["_hit"] ?: return null
        val obj =
            when (raw) {
                is JsonObject -> raw
                is JsonPrimitive -> runCatching { Json.parseToJsonElement(raw.content) as? JsonObject }.getOrNull()
                else -> null
            }
        return (obj?.get("hit") as? JsonObject) ?: obj
    }

    fun point(args: JsonObject): Pair<Int, Int>? {
        val x = (args["x"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val y = (args["y"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        return x to y
    }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}
