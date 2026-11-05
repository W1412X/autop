/**
 * 工具参数校验（docs/tech/04 §3.3）：JSON Schema 子集。
 *
 * 支持 `type`（object/string/integer/number/boolean/array）、`properties`、`required`、
 * `additionalProperties:false`、`enum`、`minimum`/`maximum`、`minLength`/`maxLength`、`items`
 * 与 DSL 生成的 `anyOf` 必填组。形状校验（object/required/anyOf）在本文件，
 * 各类型取值校验在 ArgsValues；整数/字符串/布尔参数支持宽松转换（模型常见错误）。
 */
package com.example.autop.agent.runtime.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 校验结果：错误列表为空时 [args] 为宽松转换后的参数。 */
internal data class ArgsValidation(
    val args: JsonObject?,
    val errors: List<String>,
)

/** 参数校验器（形状）。 */
internal object ArgsValidator {
    private const val KEY_TYPE = "type"
    private const val KEY_PROPERTIES = "properties"
    private const val KEY_REQUIRED = "required"
    private const val KEY_ADDITIONAL = "additionalProperties"
    private const val KEY_ANY_OF = "anyOf"
    private const val KEY_ONE_OF = "oneOf"

    /** 校验 [args] 是否符合 [schema]；失败时收集逐条错误（带参数路径）。 */
    fun validate(
        schema: JsonObject,
        args: JsonObject,
    ): ArgsValidation {
        val errors = mutableListOf<String>()
        val coerced = validateObject(schema, args, path = "", errors)
        checkOneOf(schema, args, errors)
        return ArgsValidation(if (errors.isEmpty()) coerced else null, errors)
    }

    /** 单个取值的类型分发（ArgsValues 的数组元素递归回到这里）。 */
    fun validateValue(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        errors: MutableList<String>,
    ): JsonElement {
        if (value is JsonNull) {
            errors += "$path: null not allowed"
            return value
        }
        return when (schema[KEY_TYPE]?.jsonPrimitive?.content) {
            "object" -> validateObject(schema, value, path, errors)
            "string" -> ArgsValues.validateString(schema, value, path, errors)
            "integer" -> ArgsValues.validateInteger(schema, value, path, errors)
            "number" -> ArgsValues.validateNumber(schema, value, path, errors)
            "boolean" -> ArgsValues.validateBoolean(value, path, errors)
            "array" -> ArgsValues.validateArray(schema, value, path, errors)
            else -> value
        }
    }

    private fun validateObject(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        errors: MutableList<String>,
    ): JsonObject {
        val obj = value as? JsonObject
        if (obj == null) {
            errors += "$path: expected object"
            return JsonObject(emptyMap())
        }
        val props = schema[KEY_PROPERTIES]?.jsonObject.orEmpty()
        for (name in schema[KEY_REQUIRED]?.jsonArray.orEmpty()) {
            val key = name.jsonPrimitive.content
            if (obj[key] == null || obj[key] is JsonNull) errors += "$path$key: required"
        }
        val result = mutableMapOf<String, JsonElement>()
        for ((key, child) in obj) {
            val propSchema = props[key]
            if (propSchema == null) {
                if (schema[KEY_ADDITIONAL]?.jsonPrimitive?.booleanOrNull == false) {
                    errors += "$path$key: unexpected parameter"
                }
                result[key] = child
            } else {
                result[key] = validateValue(propSchema.jsonObject, child, "$path$key.", errors)
            }
        }
        return JsonObject(result)
    }

    /**
     * `anyOf` 必填组：至少填满一组（与模型侧 schema 语义一致；兼容旧 `oneOf` 键）。
     * 单键组如 open_app 即“二选一”，多键组表示组内 key 须同时提供。
     */
    private fun checkOneOf(
        schema: JsonObject,
        args: JsonObject,
        errors: MutableList<String>,
    ) {
        val groups =
            (schema[KEY_ANY_OF]?.jsonArray ?: schema[KEY_ONE_OF]?.jsonArray).orEmpty().map { group ->
                group.jsonObject[KEY_REQUIRED]
                    ?.jsonArray
                    .orEmpty()
                    .map { it.jsonPrimitive.content }
            }
        if (groups.isEmpty()) return
        val satisfied = groups.any { group -> group.all { args[it] != null && args[it] !is JsonNull } }
        if (!satisfied) {
            errors += "one of [${groups.flatten().distinct().joinToString()}] is required"
        }
    }
}
