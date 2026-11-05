/**
 * 工具 JSON Schema 组装（docs/tech/04 §2.3）：模板正文作工具描述，
 * `meta["param.<名>"]` 作参数描述；缺少参数说明即构建失败（测试覆盖所有工具）。
 */
package com.example.autop.agent

import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 由 [ToolSpec] + prompt 模板组装发给模型的 [ToolSchema]。 */
public object ToolSchemaBuilder {
    /** 组装工具 schema；参数说明缺失是开发期错误，直接失败。 */
    public fun build(
        spec: ToolSpec,
        prompts: PromptRepository,
    ): ToolSchema {
        val template = prompts.get(spec.descriptionPromptId)
        val description =
            if (spec.descriptionVars.isEmpty()) {
                template.body
            } else {
                prompts.render(spec.descriptionPromptId, spec.descriptionVars).getOrNull()?.text
                    ?: error("模板 ${template.id} 渲染失败：缺少 ${spec.descriptionVars.keys}")
            }
        val props = spec.parameters["properties"]?.jsonObject.orEmpty()
        val described =
            props.mapValues { (name, schema) ->
                val doc =
                    template.meta["$PARAM_PREFIX$name"]
                        ?: error("模板 ${template.id} 缺少参数说明 $PARAM_PREFIX$name")
                JsonObject(schema.jsonObject + (KEY_DESCRIPTION to JsonPrimitive(doc)))
            }
        val parameters = JsonObject(spec.parameters + (KEY_PROPERTIES to JsonObject(described)))
        return ToolSchema(name = spec.name, description = description.trim(), parameters = parameters)
    }

    private const val PARAM_PREFIX = "param."
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_PROPERTIES = "properties"
}
