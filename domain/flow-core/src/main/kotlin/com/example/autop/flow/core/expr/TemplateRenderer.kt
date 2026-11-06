package com.example.autop.flow.core.expr

import com.example.autop.flow.FlowErrorCodes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 模板静态分析。 */
public object Templates {
    private const val OPEN = "{{"
    private const val CLOSE = "}}"
    private const val ESCAPED_OPEN = "\\{{"

    /** 提取模板里的全部表达式源码；缺少 `}}` 抛 [FlowException]。 */
    public fun expressions(template: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < template.length) {
            when {
                template.startsWith(ESCAPED_OPEN, i) -> i += ESCAPED_OPEN.length
                template.startsWith(OPEN, i) -> {
                    val end = template.indexOf(CLOSE, i + OPEN.length)
                    if (end < 0) throw FlowException(FlowErrorCodes.EXPR_ERROR, "模板缺少 }}：$template")
                    out += template.substring(i + OPEN.length, end)
                    i = end + CLOSE.length
                }
                else -> i++
            }
        }
        return out
    }
}

/**
 * 模板渲染（docs/design/flows.md §3.1）：`{{ expr }}`；整串恰为一个表达式时保留原类型；`\{{` 为字面量。
 */
public class TemplateRenderer(
    private val evaluator: ExprEvaluator,
) {
    private companion object {
        const val OPEN = "{{"
        const val CLOSE = "}}"
        const val ESCAPED_OPEN = "\\{{"
    }

    /** 渲染字符串模板。 */
    public fun render(template: String): JsonElement {
        if (!template.contains(OPEN)) return JsonPrimitive(template)
        val parts = split(template)
        if (parts.size == 1 && parts[0] is Part.Code) {
            return evaluator.eval((parts[0] as Part.Code).source)
        }
        val sb = StringBuilder()
        for (p in parts) {
            when (p) {
                is Part.Text -> sb.append(p.text)
                is Part.Code -> sb.append(Values.str(evaluator.eval(p.source)))
            }
        }
        return JsonPrimitive(sb.toString())
    }

    /** 渲染为文本。 */
    public fun renderString(template: String): String = Values.str(render(template))

    /** 递归渲染 JSON 中所有字符串值。 */
    public fun renderJson(element: JsonElement): JsonElement =
        when (element) {
            is JsonPrimitive -> if (element.isString) render(element.content) else element
            is JsonArray -> JsonArray(element.map { renderJson(it) })
            is JsonObject -> JsonObject(element.mapValues { renderJson(it.value) })
        }

    private sealed interface Part {
        data class Text(
            val text: String,
        ) : Part

        data class Code(
            val source: String,
        ) : Part
    }

    private fun split(template: String): List<Part> {
        val parts = mutableListOf<Part>()
        val text = StringBuilder()
        var i = 0
        while (i < template.length) {
            when {
                template.startsWith(ESCAPED_OPEN, i) -> {
                    text.append(OPEN)
                    i += ESCAPED_OPEN.length
                }
                template.startsWith(OPEN, i) -> {
                    val end = template.indexOf(CLOSE, i + OPEN.length)
                    if (end < 0) {
                        throw FlowException(FlowErrorCodes.EXPR_ERROR, "模板缺少 }}：$template")
                    }
                    if (text.isNotEmpty()) {
                        parts += Part.Text(text.toString())
                        text.clear()
                    }
                    parts += Part.Code(template.substring(i + OPEN.length, end))
                    i = end + CLOSE.length
                }
                else -> text.append(template[i++])
            }
        }
        if (text.isNotEmpty()) parts += Part.Text(text.toString())
        return parts
    }
}
