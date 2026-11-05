/**
 * 工具参数结构 DSL（docs/tech/04 §2.3）：在代码中定义参数 schema，
 * 描述文字来自 prompt 模板 meta（`param.<名>`），由 [ToolSchemaBuilder] 组装。
 */
package com.example.autop.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 定义工具参数结构（docs/tech/04 §2.3 的 `toolSpec` DSL）。 */
public fun toolSpec(
    name: String,
    descriptionPromptId: String,
    descriptionVars: Map<String, String> = emptyMap(),
    block: ToolSpecBuilder.() -> Unit,
): ToolSpec {
    val builder = ToolSpecBuilder()
    builder.block()
    return ToolSpec(
        name = name,
        parameters = builder.buildSchema(),
        descriptionPromptId = descriptionPromptId,
        descriptionVars = descriptionVars,
    )
}

/** 参数结构构建器：只描述类型与约束，不写描述文字。 */
public class ToolSpecBuilder {
    private val properties = mutableMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()
    private val oneOfGroups = mutableListOf<List<String>>()

    /** 字符串参数；[enum] 限定取值。 */
    public fun string(
        name: String,
        required: Boolean = false,
        enum: List<String>? = null,
        minLength: Int? = null,
        maxLength: Int? = null,
    ) {
        addProperty(
            name,
            required,
            buildJsonObject {
                put("type", "string")
                enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
                minLength?.let { put("minLength", it) }
                maxLength?.let { put("maxLength", it) }
            },
        )
    }

    /** 整数参数；模型常把 `"12"` 传成字符串，由 ArgsValidator 宽松转换。 */
    public fun integer(
        name: String,
        required: Boolean = false,
        min: Int? = null,
        max: Int? = null,
        enum: List<Int>? = null,
    ) {
        addProperty(
            name,
            required,
            buildJsonObject {
                put("type", "integer")
                enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
                min?.let { put("minimum", it) }
                max?.let { put("maximum", it) }
            },
        )
    }

    /** 数值参数。 */
    public fun number(
        name: String,
        required: Boolean = false,
        min: Double? = null,
        max: Double? = null,
    ) {
        addProperty(
            name,
            required,
            buildJsonObject {
                put("type", "number")
                min?.let { put("minimum", it) }
                max?.let { put("maximum", it) }
            },
        )
    }

    /** 布尔参数。 */
    public fun boolean(
        name: String,
        required: Boolean = false,
    ) {
        addProperty(name, required, buildJsonObject { put("type", "boolean") })
    }

    /**
     * 数组参数：[block] 声明元素对象的子字段（如计划项 `items`）；不传时元素为
     * [itemType] 标量，可用 [itemEnum] 限定取值、[minItems]/[maxItems] 限定长度。
     */
    public fun array(
        name: String,
        required: Boolean = false,
        itemType: String = "string",
        itemEnum: List<String>? = null,
        minItems: Int? = null,
        maxItems: Int? = null,
        block: (ToolSpecBuilder.() -> Unit)? = null,
    ) {
        val items =
            if (block == null) {
                buildJsonObject {
                    put("type", itemType)
                    itemEnum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
                }
            } else {
                val child = ToolSpecBuilder()
                child.block()
                child.buildSchema()
            }
        addProperty(
            name,
            required,
            buildJsonObject {
                put("type", "array")
                put("items", items)
                minItems?.let { put("minItems", it) }
                maxItems?.let { put("maxItems", it) }
            },
        )
    }

    /** 嵌套对象参数：[block] 内继续声明子字段。 */
    public fun obj(
        name: String,
        required: Boolean = false,
        block: ToolSpecBuilder.() -> Unit,
    ) {
        val child = ToolSpecBuilder()
        child.block()
        addProperty(name, required, child.buildSchema())
    }

    /** 任意对象参数（不约束内部字段，如 `finish` 的 `outputs`）。 */
    public fun anyObject(
        name: String,
        required: Boolean = false,
    ) {
        addProperty(name, required, buildJsonObject { put("type", "object") })
    }

    /**
     * 必填组（anyOf）：至少填满一组，如 `oneOfRequired(listOf("package"), listOf("name"))`
     *（二选一）；多键组如 `oneOfRequired(listOf("a", "b"), listOf("c"))` 表示（a 且 b）或 c。
     */
    public fun oneOfRequired(vararg groups: List<String>) {
        oneOfGroups += groups
    }

    /** 生成参数 JSON Schema（`type:object`、`properties`、`required`、`additionalProperties:false`）。 */
    public fun buildSchema(): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) {
                put("required", JsonArray(required.map(::JsonPrimitive)))
            }
            put("additionalProperties", false)
            if (oneOfGroups.isNotEmpty()) {
                // 标准 JSON Schema 的 anyOf＝至少满足一组（oneOf 是“恰好一组”，传全会被拒，弃用）。
                put(
                    "anyOf",
                    JsonArray(
                        oneOfGroups.map { group ->
                            buildJsonObject { put("required", JsonArray(group.map(::JsonPrimitive))) }
                        },
                    ),
                )
            }
        }

    private fun addProperty(
        name: String,
        isRequired: Boolean,
        schema: JsonObject,
    ) {
        properties[name] = schema
        if (isRequired) required += name
    }
}
