/**
 * 工具参数的宽松取值（docs/tech/04 §2.3）：模型常把数字/布尔传成字符串，这里统一转换。
 */
package com.example.autop.device.impl.tools

import com.example.autop.device.Rect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 字符串参数；空串视为未提供。 */
internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }

/** 整数参数（"12" 也能读出）。 */
internal fun JsonObject.int(name: String): Int? = str(name)?.toIntOrNull()

/** 长整数参数。 */
internal fun JsonObject.long(name: String): Long? = str(name)?.toLongOrNull()

/** 布尔参数（"true"/"1"/"yes" 为真）。 */
internal fun JsonObject.bool(name: String): Boolean? =
    str(name)?.let { it == "true" || it == "1" || it.equals("yes", ignoreCase = true) }

/** 嵌套对象参数。 */
internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

/** 内容区坐标范围（0–1000）。 */
internal val COORD_RANGE = 0..1000

/** 可选坐标区域（0–1000，x1<x2、y1<y2）的解析结果。 */
internal sealed interface RegionArg {
    data object None : RegionArg

    data object Malformed : RegionArg

    data class Value(
        val rect: Rect,
    ) : RegionArg
}

/** 解析 `region` 对象：缺省 [RegionArg.None]，格式或范围非法 [RegionArg.Malformed]。 */
internal fun regionOf(region: JsonObject?): RegionArg {
    if (region == null) return RegionArg.None
    val coords = REGION_KEYS.map { region.int(it) }
    if (coords.any { it == null }) return RegionArg.Malformed
    val values = coords.filterNotNull()
    val x1 = values[0]
    val y1 = values[1]
    val x2 = values[2]
    val y2 = values.last()
    val inRange = listOf(x1, y1, x2, y2).all { it in COORD_RANGE }
    val ordered = x1 < x2 && y1 < y2
    return if (inRange && ordered) RegionArg.Value(Rect(x1, y1, x2, y2)) else RegionArg.Malformed
}

private val REGION_KEYS = listOf("x1", "y1", "x2", "y2")
