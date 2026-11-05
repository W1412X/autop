package com.example.autop.core.prompt

/** 模板来源：内置（assets）或用户覆盖（正文被替换，头部沿用内置，docs/tech/02 §3.2）。 */
public enum class PromptSource {
    BUILTIN,
    USER,
}

/**
 * 一个 prompt 模板（docs/tech/02 §3.1、§3.2）。
 *
 * - [vars] 中的变量渲染时必须提供；[optionalVars] 缺失按空串；
 * - [meta] 为头部 `meta:` 一层映射（如工具模板的 `param.<参数名>` 说明）；
 * - 正文语法：`{{var}}` 替换、`{{#if var}}…{{/if}}` 非空输出（不支持嵌套）。
 */
public data class PromptTemplate(
    val id: String,
    val version: Int,
    val vars: Set<String>,
    val optionalVars: Set<String>,
    val meta: Map<String, String>,
    val body: String,
    val source: PromptSource,
)

/** 渲染结果（[text] 为替换完成的最终正文）。 */
public data class RenderedPrompt(
    val id: String,
    val version: Int,
    val text: String,
)
