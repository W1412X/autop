/**
 * 取消收口的配对补齐（docs/tech/04 §3.2）：最后一条 AssistantMessage 里
 * 未配对的 ToolCall 各补一条取消结果。以 RunController 扩展函数拆出，
 * 避免 RunController 超过函数数阈值。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.ToolResultEvent
import com.example.autop.llm.Block

/** 为最后一条 AssistantMessage 中未配对的 ToolCall 补 [cancelledText] 结果。 */
internal suspend fun RunController.pairCancelledCalls(
    cancelledText: String,
    toolIdOf: (String) -> String,
) {
    val events = toolStore.events(threadId)
    val paired = events.mapNotNull { (it.payload as? ToolResultEvent)?.callId }.toSet()
    val last = events.lastOrNull { it.payload is AssistantMessage }?.payload as? AssistantMessage ?: return
    for (block in last.blocks) {
        if (block is Block.ToolCall && block.id !in paired) {
            append(
                ToolResultEvent(
                    callId = block.id,
                    toolId = toolIdOf(block.name),
                    ok = false,
                    blocks = listOf(Block.Text(cancelledText)),
                    durationMs = 0,
                ),
            )
        }
    }
}
