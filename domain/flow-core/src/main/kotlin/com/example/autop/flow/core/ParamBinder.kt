package com.example.autop.flow.core

import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowParam
import com.example.autop.flow.ParamType
import com.example.autop.flow.core.expr.FlowException
import com.example.autop.flow.core.expr.Values
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 把传入值按参数声明绑定：补默认值、检查必填、做类型转换、检查取值范围（§2.2）。 */
public object ParamBinder {
    /** 失败抛 [FlowException]（`flow.param_missing` / `flow.args_invalid`）。 */
    public fun bind(
        declared: List<FlowParam>,
        given: JsonObject,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (p in declared) {
            val raw = given[p.name]?.takeUnless { it is JsonNull } ?: p.default
            if (raw == null || raw is JsonNull) {
                if (p.required) throw FlowException(FlowErrorCodes.PARAM_MISSING, "缺少必填参数：${p.name}")
                out[p.name] = JsonNull
                continue
            }
            val converted = convert(p, raw)
            val choices = p.choices
            if (choices != null && choices.none { Values.equal(it, converted) }) {
                throw FlowException(FlowErrorCodes.ARGS_INVALID, "参数 ${p.name} 的值不在允许范围内")
            }
            out[p.name] = converted
        }
        // 未声明的额外参数保留，便于 AI 生成的流程宽容调用。
        for ((k, v) in given) if (k !in out) out[k] = v
        return JsonObject(out)
    }

    private fun convert(
        p: FlowParam,
        raw: JsonElement,
    ): JsonElement =
        when (p.type) {
            ParamType.ANY -> raw
            ParamType.STRING -> if (raw is JsonPrimitive) JsonPrimitive(Values.str(raw)) else bad(p, "文本")
            ParamType.NUMBER, ParamType.TIME ->
                Values.numOrNull(raw)?.let { Values.num(it) } ?: bad(p, "数字")
            ParamType.BOOLEAN -> toBool(raw) ?: bad(p, "布尔")
            ParamType.LIST -> raw as? JsonArray ?: bad(p, "列表")
            ParamType.OBJECT -> raw as? JsonObject ?: bad(p, "对象")
        }

    private fun toBool(raw: JsonElement): JsonElement? {
        val prim = raw as? JsonPrimitive ?: return null
        prim.booleanOrNull?.let { return JsonPrimitive(it) }
        return when (prim.content.lowercase()) {
            "true", "是", "1" -> JsonPrimitive(true)
            "false", "否", "0" -> JsonPrimitive(false)
            else -> null
        }
    }

    private fun bad(
        p: FlowParam,
        expected: String,
    ): Nothing = throw FlowException(FlowErrorCodes.ARGS_INVALID, "参数 ${p.name} 需要$expected")
}
