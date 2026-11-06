@file:Suppress("CyclomaticComplexMethod", "ComplexCondition") // 值类型的转换矩阵，分支数由类型组合决定

package com.example.autop.flow.core.expr

import com.example.autop.flow.FlowErrorCodes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** 运行期值（统一用 [JsonElement]）的转换与比较。 */
public object Values {
    private const val MAX_EXACT_LONG: Double = 9.0e15

    /** 数字值：整数值保留整数形态。 */
    public fun num(v: Double): JsonElement =
        if (v == Math.rint(v) && kotlin.math.abs(v) < MAX_EXACT_LONG) JsonPrimitive(v.toLong()) else JsonPrimitive(v)

    public fun of(raw: Any?): JsonElement =
        when (raw) {
            null -> JsonNull
            is JsonElement -> raw
            is Boolean -> JsonPrimitive(raw)
            is Int -> JsonPrimitive(raw)
            is Long -> JsonPrimitive(raw)
            is Double -> num(raw)
            is Number -> num(raw.toDouble())
            is String -> JsonPrimitive(raw)
            is List<*> -> JsonArray(raw.map { of(it) })
            is Map<*, *> -> JsonObject(raw.entries.associate { it.key.toString() to of(it.value) })
            else -> JsonPrimitive(raw.toString())
        }

    public fun isNull(v: JsonElement?): Boolean = v == null || v is JsonNull

    public fun isString(v: JsonElement?): Boolean = v is JsonPrimitive && v.isString

    /** 转字符串（模板拼接、`str()`）。 */
    public fun str(v: JsonElement?): String =
        when (v) {
            null, is JsonNull -> ""
            is JsonPrimitive -> if (v.isString) v.content else numberText(v)
            else -> v.toString()
        }

    private fun numberText(p: JsonPrimitive): String {
        val d = p.doubleOrNull
        return if (d != null && p.longOrNull == null && d == Math.rint(d) && kotlin.math.abs(d) < MAX_EXACT_LONG) {
            d.toLong().toString()
        } else {
            p.content
        }
    }

    /** 转数字；字符串按数字解析，失败为 null。 */
    public fun numOrNull(v: JsonElement?): Double? =
        when (v) {
            is JsonPrimitive -> if (v.isString) v.content.trim().toDoubleOrNull() else v.doubleOrNull
            else -> null
        }

    public fun requireNum(
        v: JsonElement?,
        what: String,
    ): Double = numOrNull(v) ?: throw FlowException(FlowErrorCodes.TYPE_ERROR, "$what 需要数字，实际是 ${typeOf(v)}")

    public fun requireStr(
        v: JsonElement?,
        what: String,
    ): String =
        when (v) {
            is JsonPrimitive -> str(v)
            else -> throw FlowException(FlowErrorCodes.TYPE_ERROR, "$what 需要文本，实际是 ${typeOf(v)}")
        }

    public fun requireList(
        v: JsonElement?,
        what: String,
    ): List<JsonElement> =
        (v as? JsonArray) ?: throw FlowException(FlowErrorCodes.TYPE_ERROR, "$what 需要列表，实际是 ${typeOf(v)}")

    public fun requireObject(
        v: JsonElement?,
        what: String,
    ): JsonObject = (v as? JsonObject) ?: throw FlowException(FlowErrorCodes.TYPE_ERROR, "$what 需要对象，实际是 ${typeOf(v)}")

    /** 真值：null/false/0/空串/空集合 为假。 */
    public fun truthy(v: JsonElement?): Boolean =
        when (v) {
            null, is JsonNull -> false
            is JsonPrimitive ->
                when {
                    v.isString -> v.content.isNotEmpty()
                    v.booleanOrNull != null -> v.booleanOrNull == true
                    else -> (v.doubleOrNull ?: 0.0) != 0.0
                }
            is JsonArray -> v.isNotEmpty()
            is JsonObject -> v.isNotEmpty()
        }

    public fun typeOf(v: JsonElement?): String =
        when (v) {
            null, is JsonNull -> "null"
            is JsonArray -> "list"
            is JsonObject -> "object"
            is JsonPrimitive ->
                when {
                    v.isString -> "string"
                    v.booleanOrNull != null -> "boolean"
                    else -> "number"
                }
        }

    /** `==` 语义：数字与数字字符串互转比较，其余严格比较。 */
    public fun equal(
        a: JsonElement?,
        b: JsonElement?,
    ): Boolean {
        if (isNull(a) || isNull(b)) return isNull(a) && isNull(b)
        val ap = a as? JsonPrimitive
        val bp = b as? JsonPrimitive
        if (ap == null || bp == null) return a == b
        return when {
            ap.isString && bp.isString -> ap.content == bp.content
            ap.booleanOrNull != null || bp.booleanOrNull != null ->
                !ap.isString && !bp.isString && ap.booleanOrNull == bp.booleanOrNull
            else -> {
                val an = numOrNull(ap)
                val bn = numOrNull(bp)
                an != null && bn != null && an == bn
            }
        }
    }

    /** 大小比较：两边都是数字（或数字字符串）按数值，否则按文本。 */
    public fun compare(
        a: JsonElement?,
        b: JsonElement?,
    ): Int {
        val an = numOrNull(a)
        val bn = numOrNull(b)
        return if (an != null && bn != null) an.compareTo(bn) else str(a).compareTo(str(b))
    }

    /** 体积估算（字节），用于变量上限。 */
    public fun sizeBytes(v: JsonElement?): Int = v?.toString()?.length?.times(2) ?: 0
}
