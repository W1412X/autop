/**
 * finish.outputs 校验（docs/tech/07 §1.1 `Task.outputSchema`，R1/C2）：
 * JSON Schema 子集（type/properties/required），后台任务结束时按它校验，
 * 不通过拒绝结束。返回问题列表（空 = 通过）。
 */
package com.example.autop.task.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** finish.outputs 的 JSON Schema 子集校验。 */
public object OutputSchemaValidator {
    /** 问题描述列表（空表示通过）。 */
    public fun validate(
        outputs: JsonObject,
        schema: JsonObject,
    ): List<String> {
        if (!isObjectSchema(schema)) return listOf("schema 必须是 type=object 的对象模式")
        val problems = mutableListOf<String>()
        problems += missingRequired(outputs, schema)
        problems += propertyProblems(outputs, schema)
        return problems
    }

    private fun isObjectSchema(schema: JsonObject): Boolean = schema.string("type") == TYPE_OBJECT

    private fun missingRequired(
        outputs: JsonObject,
        schema: JsonObject,
    ): List<String> {
        val required = schema[KEY_REQUIRED] as? JsonArray ?: return emptyList()
        return required
            .filterIsInstance<JsonPrimitive>()
            .mapNotNull { it.contentOrNull }
            .filter { it !in outputs }
            .map { "缺少必填字段 $it" }
    }

    private fun propertyProblems(
        outputs: JsonObject,
        schema: JsonObject,
    ): List<String> {
        val properties = schema[KEY_PROPERTIES] as? JsonObject ?: return emptyList()
        return properties.keys
            .filter { it in outputs }
            .flatMap { key ->
                val propertySchema = properties[key] as? JsonObject
                if (propertySchema == null) {
                    emptyList()
                } else {
                    checkType(key, outputs.getValue(key), propertySchema.string("type"))
                }
            }
    }

    private fun checkType(
        key: String,
        value: JsonElement,
        type: String?,
    ): List<String> {
        if (value is JsonNull) return listOf("字段 $key 不允许为 null")
        val ok =
            when (type) {
                TYPE_STRING -> value.isString
                TYPE_NUMBER -> value.isNumber
                TYPE_INTEGER -> value.isInt
                TYPE_BOOLEAN -> value.isBool
                TYPE_ARRAY -> value is JsonArray
                TYPE_OBJECT -> value is JsonObject
                else -> true
            }
        return if (ok) emptyList() else listOf("字段 $key 类型不符，期望 $type")
    }

    // JSON 原生类型判定：带引号的字符串内容（如 "true"/"26"）不得伪装成 number/integer/boolean。
    private val JsonElement.isString: Boolean get() = (this as? JsonPrimitive)?.isString == true

    private val JsonElement.isNumber: Boolean
        get() = (this as? JsonPrimitive)?.let { !it.isString && it.contentOrNull?.toDoubleOrNull() != null } == true

    private val JsonElement.isInt: Boolean
        get() = (this as? JsonPrimitive)?.let { !it.isString && it.intOrNull != null } == true

    private val JsonElement.isBool: Boolean
        get() = (this as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull != null } == true

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private const val KEY_PROPERTIES = "properties"
    private const val KEY_REQUIRED = "required"
    private const val TYPE_OBJECT = "object"
    private const val TYPE_ARRAY = "array"
    private const val TYPE_STRING = "string"
    private const val TYPE_NUMBER = "number"
    private const val TYPE_INTEGER = "integer"
    private const val TYPE_BOOLEAN = "boolean"
}
