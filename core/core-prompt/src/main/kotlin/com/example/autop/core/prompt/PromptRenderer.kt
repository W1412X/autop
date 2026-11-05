package com.example.autop.core.prompt

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome

/**
 * 渲染模板（docs/tech/02 §3.1）：`{{var}}` 替换、`{{#if var}}…{{/if}}` 非空输出。
 *
 * [PromptTemplate.vars] 中（除 [PromptTemplate.optionalVars]）的变量缺失 →
 * `prompt.missing_var`；optionalVars 缺失按空串。模板结构与变量声明已在加载期校验。
 */
public fun renderPrompt(
    template: PromptTemplate,
    vars: Map<String, String> = emptyMap(),
): Outcome<RenderedPrompt> {
    val missing = template.vars - template.optionalVars - vars.keys
    if (missing.isNotEmpty()) {
        return Outcome.Err(
            AppError(
                code = PromptErrorCodes.MISSING_VAR,
                message = "missing variables ${missing.sorted()} for template '${template.id}'",
            ),
        )
    }
    val values = template.vars.associateWith { vars[it] ?: "" }
    return Outcome.Ok(RenderedPrompt(template.id, template.version, renderBody(template.body, values)))
}

private fun renderBody(
    body: String,
    values: Map<String, String>,
): String {
    val out = StringBuilder()
    var cursor = 0
    var suppressed = false
    for (match in TEMPLATE_TOKEN.findAll(body)) {
        if (!suppressed) out.append(body, cursor, match.range.first)
        cursor = match.range.last + 1
        val token = match.groupValues[1].trim()
        when {
            token == "/if" -> suppressed = false
            token.startsWith("#if ") -> suppressed = values[token.removePrefix("#if ").trim()].isNullOrEmpty()
            !suppressed -> out.append(values[token])
        }
    }
    if (!suppressed) out.append(body, cursor, body.length)
    return out.toString()
}
