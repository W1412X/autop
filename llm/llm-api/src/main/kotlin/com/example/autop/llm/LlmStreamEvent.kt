/**
 * 流式事件（docs/tech/03 §2）：适配器把线格式增量翻译成统一事件，
 * 末尾固定以 [LlmStreamEvent.Completed] 收束。
 */
package com.example.autop.llm

import kotlinx.serialization.Serializable

/** 用量统计（tokens）；随 AssistantMessage 持久化（docs/tech/04 §2.1）。 */
@Serializable
public data class Usage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
)

/** 停止原因（统一枚举，适配器映射各自线格式取值）。 */
@Serializable
public enum class StopReason {
    END_TURN,
    TOOL_USE,
    MAX_TOKENS,
    STOP_SEQUENCE,
    REFUSAL,
    OTHER,
}

/** 一次流式补全的事件序列（docs/tech/03 §2）。 */
public sealed interface LlmStreamEvent {
    /** 文本增量。 */
    public data class TextDelta(
        val text: String,
    ) : LlmStreamEvent

    /** 思考增量（只用于 UI 展示，不回传）。 */
    public data class ThinkingDelta(
        val text: String,
    ) : LlmStreamEvent

    /** 工具调用开始（[index] 为流内序号）。 */
    public data class ToolCallStarted(
        val index: Int,
        val id: String,
        val name: String,
    ) : LlmStreamEvent

    /** 工具参数增量（[fragment] 拼接后为 argsJson）。 */
    public data class ToolCallArgsDelta(
        val index: Int,
        val fragment: String,
    ) : LlmStreamEvent

    /** 用量上报（通常在末尾）。 */
    public data class UsageReported(
        val usage: Usage,
    ) : LlmStreamEvent

    /** 最后一个事件：适配器组装好的完整消息。 */
    public data class Completed(
        val message: Msg,
        val stopReason: StopReason,
        val usage: Usage?,
    ) : LlmStreamEvent
}
