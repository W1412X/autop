/**
 * 工具参数的取值校验（docs/tech/04 §3.3）：string/integer/number/boolean/array
 * 的类型与约束（enum/min-max/length/items）；数值/布尔参数接受 `"12"`/`"true"` 等
 * 字符串化形式并转换，string 参数接受数字/布尔取 content（模型常见错误，两层对称）。
 * 形状校验（object/required/anyOf）见 ArgsValidator。
 */
package com.example.autop.agent.runtime.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 取值校验器。 */
internal object ArgsValues {
    private const val KEY_ENUM = "enum"
    private const val KEY_ITEMS = "items"

    fun validateString(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        errors: MutableList<String>,
    ): JsonElement {
        val primitive = value as? JsonPrimitive
        if (primitive == null) {
            errors += "$path: expected string"
            return value
        }
        // 模型常把数字/布尔传给 string 参数（如 {"id": 3}），宽松取 content（与整数参数对称）。
        val coerced = JsonPrimitive(primitive.content)
        checkEnum(schema, coerced, path, errors)
        schema["minLength"]?.jsonPrimitive?.intOrNull?.let { min ->
            if (coerced.content.length < min) errors += "$path: shorter than $min"
        }
        schema["maxLength"]?.jsonPrimitive?.intOrNull?.let { max ->
            if (coerced.content.length > max) errors += "$path: longer than $max"
        }
        return coerced
    }

    fun validateInteger(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        errors: MutableList<String>,
    ): JsonElement {
        val num =
            when (value) {
                is JsonPrimitive -> value.intOrNull ?: value.contentOrNull()?.toIntOrNull()
                else -> null
            }
        if (num == null) {
            errors += "$path: expected integer"
            return value
        }
        val coerced = JsonPrimitive(num)
        checkEnum(schema, coerced, path, errors)
        checkRange(schema, num.toDouble(), path, errors)
        return coerced
    }

    fun validateNumber(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        errors: MutableList<String>,
    ): JsonElement {
        val num = (value as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
        if (num == null) {
            errors += "$path: expected number"
            return value
        }
        val coerced = JsonPrimitive(num)
        checkEnum(schema, coerced, path, errors)
        checkRange(schema, num, path, errors)
        return coerced
    }

    fun validateBoolean(
        value: JsonElement,
        path: String,
        errors: MutableList<String>,
    ): JsonElement {
        val bool = (value as? JsonPrimitive)?.let { parseBoolean(it) }
        if (bool == null) {
            errors += "$path: expected boolean"
            return value
        }
        return JsonPrimitive(bool)
    }

    /** 宽松布尔（与工具层取值对称）："true"/"1"/"yes" 为真，"false"/"0"/"no" 为假。 */
    private fun parseBoolean(value: JsonPrimitive): Boolean? {
        value.booleanOrNull?.let { return it }
        return when (value.content.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }

    fun validateArray(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        errors: MutableList<String>,
    ): JsonElement {
        val array = value as? JsonArray
        val items = schema[KEY_ITEMS]?.jsonObject
        return when {
            array == null -> {
                errors += "$path: expected array"
                value
            }
            items == null -> value
            else ->
                JsonArray(
                    array.mapIndexed { i, item ->
                        ArgsValidator.validateValue(items, item, "$path$i.", errors)
                    },
                )
        }
    }

    private fun checkEnum(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        errors: MutableList<String>,
    ) {
        val allowed = schema[KEY_ENUM]?.jsonArray ?: return
        if (value !in allowed) errors += "$path: not one of ${allowed.joinToString()}"
    }

    private fun checkRange(
        schema: JsonObject,
        num: Double,
        path: String,
        errors: MutableList<String>,
    ) {
        schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { min ->
            if (num < min) errors += "$path: less than $min"
        }
        schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { max ->
            if (num > max) errors += "$path: greater than $max"
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null
}
