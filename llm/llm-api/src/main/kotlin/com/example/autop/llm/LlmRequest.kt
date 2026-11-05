/**
 * LLM 请求（docs/tech/03 §2）：system 分段传递，消息与工具同请求携带。
 */
package com.example.autop.llm

import kotlinx.serialization.json.JsonObject

/** system 段（[cacheBreakpoint] 为 Anthropic 的缓存标记，docs/tech/03 §5.2）。 */
public data class SystemPart(
    val text: String,
    val cacheBreakpoint: Boolean = false,
)

/** 工具声明（JSON Schema）。 */
public data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * 工具选择策略（docs/design/observation-modes-coordinate-actions §4.3）：
 * [required] 强制调用指定工具，用于 llm 观测模式的 `report_elements`。
 */
public sealed interface ToolChoice {
    public data object AUTO : ToolChoice

    public data object NONE : ToolChoice

    public data object REQUIRED : ToolChoice

    /** 强制调用名为 [name] 的工具。 */
    public data class Named(
        val name: String,
    ) : ToolChoice

    public companion object {
        public fun required(name: String): ToolChoice = Named(name)
    }
}

/** 一次对话补全请求（docs/tech/03 §2）。 */
public data class LlmRequest(
    val system: List<SystemPart>,
    val messages: List<Msg>,
    val tools: List<ToolSchema> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.AUTO,
    val maxOutputTokens: Int,
    val temperature: Double? = null,
    val thinkingBudgetTokens: Int? = null,
    val cacheBreakpointMessageIndex: Int? = null,
)
