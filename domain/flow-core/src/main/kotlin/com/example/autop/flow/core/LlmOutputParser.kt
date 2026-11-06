@file:Suppress("CyclomaticComplexMethod", "ReturnCount", "LoopWithTooManyJumpStatements") // 输出解析与 JSON Schema 子集校验逐项早退

package com.example.autop.flow.core

import com.example.autop.flow.LlmOutput
import com.example.autop.flow.LlmOutputType
import com.example.autop.flow.Validator
import com.example.autop.flow.core.expr.Values
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 解析/校验结果。 */
public sealed interface Parsed {
    public data class Value(
        val value: JsonElement,
    ) : Parsed

    /** [reason] 会作为反馈回灌给模型重试。 */
    public data class Invalid(
        val reason: String,
    ) : Parsed
}

/** LLM 输出的解析与校验（docs/design/flows.md §6.5）。纯函数。 */
public object LlmOutputParser {
    private val lenient = Json { isLenient = true }
    private val TRUE_WORDS = setOf("true", "yes", "y", "是", "对", "真", "1")
    private val FALSE_WORDS = setOf("false", "no", "n", "否", "不", "假", "0")
    private val NUMBER = Regex("-?\\d+(?:\\.\\d+)?")
    private val FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```")

    /** 按输出类型解析。 */
    public fun parse(
        text: String,
        output: LlmOutput,
    ): Parsed =
        when (output.type) {
            LlmOutputType.TEXT -> Parsed.Value(JsonPrimitive(text.trim()))
            LlmOutputType.JSON -> parseJson(text, output.schema)
            LlmOutputType.LABEL -> parseLabel(text, output.labels.orEmpty())
            LlmOutputType.BOOL -> parseBool(text)
            LlmOutputType.NUMBER -> parseNumber(text)
        }

    private fun parseJson(
        text: String,
        schema: JsonObject?,
    ): Parsed {
        val candidate = extractJson(text) ?: return Parsed.Invalid("输出不是合法 JSON")
        val element =
            try {
                lenient.parseToJsonElement(candidate)
            } catch (_: IllegalArgumentException) {
                return Parsed.Invalid("输出不是合法 JSON")
            }
        val problem = schema?.let { checkSchema(element, it, "") }
        return if (problem == null) Parsed.Value(element) else Parsed.Invalid(problem)
    }

    private fun extractJson(text: String): String? {
        FENCE.find(text)?.let { return it.groupValues[1].trim() }
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
        val start = trimmed.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val close = if (trimmed[start] == '{') '}' else ']'
        val end = trimmed.lastIndexOf(close)
        return if (end > start) trimmed.substring(start, end + 1) else null
    }

    /** 轻量 JSON Schema 子集：type / required / properties / items / enum。 */
    private fun checkSchema(
        value: JsonElement,
        schema: JsonObject,
        path: String,
    ): String? {
        val where = path.ifEmpty { "根" }
        typeProblem(value, schema, where)?.let { return it }
        enumProblem(value, schema, where)?.let { return it }
        if (value is JsonObject) {
            val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            required.firstOrNull { !value.containsKey(it) }?.let { return "缺少字段 $path.$it".replace("..", ".") }
            val props = schema["properties"] as? JsonObject
            for ((k, sub) in props.orEmpty()) {
                val v = value[k] ?: continue
                val s = sub as? JsonObject ?: continue
                checkSchema(v, s, if (path.isEmpty()) k else "$path.$k")?.let { return it }
            }
        }
        if (value is JsonArray) {
            val items = schema["items"] as? JsonObject
            if (items != null) {
                value.forEachIndexed { i, v -> checkSchema(v, items, "$path[$i]")?.let { return it } }
            }
        }
        return null
    }

    private fun typeProblem(
        value: JsonElement,
        schema: JsonObject,
        where: String,
    ): String? {
        val type = (schema["type"] as? JsonPrimitive)?.content ?: return null
        val actual = Values.typeOf(value)
        val ok =
            when (type) {
                "string" -> actual == "string"
                "number", "integer" -> actual == "number"
                "boolean" -> actual == "boolean"
                "array" -> actual == "list"
                "object" -> actual == "object"
                "null" -> actual == "null"
                else -> true
            }
        return if (ok) null else "$where 应为 $type，实际是 $actual"
    }

    private fun enumProblem(
        value: JsonElement,
        schema: JsonObject,
        where: String,
    ): String? {
        val allowed = schema["enum"] as? JsonArray ?: return null
        return if (allowed.any { Values.equal(it, value) }) null else "$where 的值不在允许范围内"
    }

    private fun parseLabel(
        text: String,
        labels: List<String>,
    ): Parsed {
        if (labels.isEmpty()) return Parsed.Value(JsonPrimitive(text.trim()))
        val cleaned = text.trim().trim('"', '\'', '。', '.', '`')
        labels.firstOrNull { it.equals(cleaned, ignoreCase = true) }?.let { return Parsed.Value(JsonPrimitive(it)) }
        val contained = labels.filter { text.contains(it, ignoreCase = true) }
        if (contained.size == 1) return Parsed.Value(JsonPrimitive(contained.first()))
        return Parsed.Invalid("输出必须恰好是这些标签之一：${labels.joinToString("、")}")
    }

    private fun parseBool(text: String): Parsed {
        val word = text.trim().trim('"', '\'', '。', '.', '`').lowercase()
        if (word in TRUE_WORDS) return Parsed.Value(JsonPrimitive(true))
        if (word in FALSE_WORDS) return Parsed.Value(JsonPrimitive(false))
        val lowered = text.lowercase()
        val hasTrue = TRUE_WORDS.any { it.length > 1 && lowered.contains(it) }
        val hasFalse = FALSE_WORDS.any { it.length > 1 && lowered.contains(it) }
        return when {
            hasTrue && !hasFalse -> Parsed.Value(JsonPrimitive(true))
            hasFalse && !hasTrue -> Parsed.Value(JsonPrimitive(false))
            else -> Parsed.Invalid("输出必须是 true 或 false")
        }
    }

    private fun parseNumber(text: String): Parsed {
        val m = NUMBER.find(text) ?: return Parsed.Invalid("输出中没有数字")
        return Parsed.Value(Values.num(m.value.toDouble()))
    }

    /**
     * 运行校验器；返回第一个不通过的说明，全部通过返回 null。
     * [evalExpr] 计算 `expr` 校验器（`out` 已绑定为本次输出）。
     */
    public fun validate(
        value: JsonElement,
        validators: List<Validator>,
        evalExpr: (String, JsonElement) -> Boolean,
    ): String? {
        for (v in validators) {
            val problem = check(value, v, evalExpr)
            if (problem != null) return v.message ?: problem
        }
        return null
    }

    private fun check(
        value: JsonElement,
        v: Validator,
        evalExpr: (String, JsonElement) -> Boolean,
    ): String? {
        val text = Values.str(value)
        return when (v) {
            is Validator.MaxLen -> if (text.length <= v.n) null else "长度不得超过 ${v.n} 字（当前 ${text.length}）"
            is Validator.MinLen -> if (text.length >= v.n) null else "长度不得少于 ${v.n} 字（当前 ${text.length}）"
            is Validator.Regex -> {
                val matched = runCatching { Regex(v.pattern).containsMatchIn(text) }.getOrDefault(false)
                if (matched == v.mustMatch) null else "格式不符合要求"
            }
            is Validator.NotContains -> v.values.firstOrNull { text.contains(it) }?.let { "不得包含「$it」" }
            is Validator.OneOf -> if (v.values.any { Values.equal(it, value) }) null else "取值不在允许范围内"
            is Validator.Range -> rangeProblem(value, v)
            is Validator.Expr -> if (evalExpr(v.expr, value)) null else "不满足条件 ${v.expr}"
        }
    }

    private fun rangeProblem(
        value: JsonElement,
        v: Validator.Range,
    ): String? {
        val n = Values.numOrNull(value) ?: return "需要数字"
        if (v.min != null && n < v.min!!) return "不得小于 ${Values.str(Values.num(v.min!!))}"
        if (v.max != null && n > v.max!!) return "不得大于 ${Values.str(Values.num(v.max!!))}"
        return null
    }

    /** 供 [JsonNull] 判空。 */
    internal fun isNullValue(v: JsonElement): Boolean =
        v is JsonNull || (v is JsonPrimitive && v.booleanOrNull == null && v.content == "null")
}
