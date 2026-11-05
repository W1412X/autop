/**
 * OpenAI 流式 chunk → 统一流事件（docs/tech/03 §5.1 流解析）。
 * Thinking 增量同样累积进 [LlmStreamEvent.Completed]（[Block.Thinking]，供持久化与
 * UI 展示）；回传请求时由 RequestMapper 剔除（不发回服务端）。
 */
package com.example.autop.llm.openai

import com.example.autop.llm.Block
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.StopReason
import com.example.autop.llm.Usage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.TreeMap

public class OpenAiStreamParser(
    private val reasoningField: String,
) {
    private val text = StringBuilder()
    private val thinking = StringBuilder()
    private val toolCalls = TreeMap<Int, PendingCall>()
    private var stopReason: StopReason? = null
    private var usage: Usage? = null
    private var generatedIds = 0

    /** 投喂一个 chunk（[DONE] 由 Provider 处理），返回本 chunk 产生的事件。 */
    public fun feed(chunk: JsonObject): List<LlmStreamEvent> {
        val events = mutableListOf<LlmStreamEvent>()
        parseUsage(chunk)?.let { events += LlmStreamEvent.UsageReported(it) }
        // 显式 null 字段按缺失处理（兼容服务常见 "tool_calls": null / "choices": null）
        val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val delta = choice?.get("delta") as? JsonObject
        if (delta != null) parseDelta(delta, events)
        choice
            ?.get("finish_reason")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let { stopReason = mapStopReason(it) }
        return events
    }

    /** 流结束：组装完整消息（思考块 + 文本块 + 每个工具调用；argsJson 空串改 `"{}"`）。 */
    public fun finish(): LlmStreamEvent.Completed {
        val blocks = mutableListOf<Block>()
        if (thinking.isNotEmpty()) blocks += Block.Thinking(thinking.toString())
        if (text.isNotEmpty()) blocks += Block.Text(text.toString())
        for (call in toolCalls.values) {
            blocks += Block.ToolCall(call.id, call.name, call.args.toString().ifEmpty { "{}" })
        }
        val reason = stopReason ?: if (toolCalls.isNotEmpty()) StopReason.TOOL_USE else StopReason.END_TURN
        return LlmStreamEvent.Completed(Msg(Role.ASSISTANT, blocks), reason, usage)
    }

    private fun parseDelta(
        delta: JsonObject,
        events: MutableList<LlmStreamEvent>,
    ) {
        delta["content"]?.jsonPrimitive?.contentOrNull?.let {
            text.append(it)
            events += LlmStreamEvent.TextDelta(it)
        }
        delta[reasoningField]?.jsonPrimitive?.contentOrNull?.let {
            thinking.append(it)
            events += LlmStreamEvent.ThinkingDelta(it)
        }
        (delta["tool_calls"] as? JsonArray)?.let { parseToolCalls(it, events) }
    }

    private fun parseToolCalls(
        deltas: JsonArray,
        events: MutableList<LlmStreamEvent>,
    ) {
        for (delta in deltas) parseToolCall(delta, events)
    }

    private fun parseToolCall(
        delta: JsonElement,
        events: MutableList<LlmStreamEvent>,
    ) {
        val obj = delta as? JsonObject ?: return
        val index = obj["index"]?.jsonPrimitive?.intOrNull ?: return
        val call = toolCalls.getOrPut(index) { PendingCall() }
        val function = obj["function"] as? JsonObject
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
        val name = function?.get("name")?.jsonPrimitive?.contentOrNull
        if (!call.started && (id != null || name != null)) {
            // 有的服务不给 id → 生成 call_<index>_<序号>（docs/tech/03 §5.1）
            call.id = id ?: "call_${index}_${generatedIds++}"
            call.name = name.orEmpty()
            call.started = true
            events += LlmStreamEvent.ToolCallStarted(index, call.id, call.name)
        }
        function?.get("arguments")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            call.args.append(it)
            events += LlmStreamEvent.ToolCallArgsDelta(index, it)
        }
    }

    private fun parseUsage(chunk: JsonObject): Usage? {
        val raw = chunk["usage"] as? JsonObject ?: return null
        val current =
            Usage(
                inputTokens = raw["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                outputTokens = raw["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                cacheReadTokens =
                    (raw["prompt_tokens_details"] as? JsonObject)
                        ?.get("cached_tokens")
                        ?.jsonPrimitive
                        ?.intOrNull ?: 0,
            )
        usage = current
        return current
    }

    private fun mapStopReason(raw: String): StopReason =
        when (raw) {
            "stop" -> StopReason.END_TURN
            "tool_calls" -> StopReason.TOOL_USE
            "length" -> StopReason.MAX_TOKENS
            "content_filter" -> StopReason.REFUSAL
            else -> StopReason.OTHER
        }

    private class PendingCall {
        var id: String = ""
        var name: String = ""
        val args: StringBuilder = StringBuilder()
        var started: Boolean = false
    }
}
