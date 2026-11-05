/**
 * 调试页工具输出的展示辅助（docs/tech/08 §4.6"显示 ToolOutput"）。
 */
package com.example.autop.feature.settings.debug

import com.example.autop.agent.ToolControl
import com.example.autop.agent.ToolOutput
import com.example.autop.llm.Block
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 参数 JSON 文本 → [JsonObject]；空文本视为无参数，解析失败返回 null。 */
internal fun parseArgs(text: String): JsonObject? {
    if (text.isBlank()) return JsonObject(emptyMap())
    return runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}

/** ToolOutput 的文本块拼接（给模型看的正文）。 */
internal fun textOf(output: ToolOutput): String =
    output.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }

/** ToolOutput → 展示视图。 */
internal fun outcomeOf(
    toolId: String,
    output: ToolOutput,
): DebugToolOutcome =
    DebugToolOutcome(
        toolId = toolId,
        ok = output.ok,
        text = textOf(output),
        errorCode = output.error?.code,
        errorMessage = output.error?.message,
        control = controlOf(output.control),
    )

/** 控制指令的展示文本（如 `Pause(POLICY_BLOCKED, rule-1)`）。 */
internal fun controlOf(control: ToolControl?): String? =
    when (control) {
        is ToolControl.Pause -> "Pause(${control.reason}, ${control.detail.orEmpty()})"
        is ToolControl.Finish -> "Finish(${control.status})"
        null -> null
    }
