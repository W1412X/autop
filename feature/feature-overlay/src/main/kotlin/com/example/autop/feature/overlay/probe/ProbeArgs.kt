/**
 * 工具测试面板的参数组装（N207）：按 [ToolProbeParam] 元数据把逐键编辑值序列化成
 * 参数 JSON 文本。空值省略（未填的可选参数不进 JSON）；数值/布尔按键声明类型出
 * JSON 值，解析失败原样出字符串（工具侧报参数错误也是有效诊断输出）。
 */
package com.example.autop.feature.overlay.probe

import com.example.autop.agent.ToolParamKind
import com.example.autop.agent.ToolProbeParam
import com.example.autop.agent.ToolProbeTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 逐键编辑值 → 参数 JSON 文本（执行时传 [com.example.autop.agent.ToolProbeService.probe]）。 */
internal fun argsJsonOf(
    params: List<ToolProbeParam>,
    values: Map<String, String>,
): String {
    val entries =
        params.mapNotNull { param ->
            val text = values[param.name]?.trim().orEmpty()
            if (text.isEmpty()) {
                null
            } else {
                param.name to jsonValueOf(param.kind, text)
            }
        }
    return JsonObject(entries.toMap()).toString()
}

/** 默认编辑值：示例回填（每键可单独改或清空）。 */
internal fun defaultValuesOf(target: ToolProbeTarget): Map<String, String> =
    target.params.associate { it.name to it.example }

/** 编辑文本 → JSON 值（按参数类型；坏输入降级为字符串，不挡执行）。 */
private fun jsonValueOf(
    kind: ToolParamKind,
    text: String,
): JsonElement =
    when (kind) {
        ToolParamKind.INTEGER -> text.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text)
        ToolParamKind.NUMBER -> text.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text)
        ToolParamKind.BOOLEAN -> JsonPrimitive(text.toBoolean())
        ToolParamKind.OBJECT, ToolParamKind.ARRAY ->
            runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: JsonPrimitive(text)
        ToolParamKind.STRING -> JsonPrimitive(text)
    }
