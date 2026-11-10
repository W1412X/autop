/**
 * `tool.history_recall`（docs/tech/04 §3.9）：按 seq 范围取事件原文（分页、单次不超过
 * `agent.recall.maxChars`），或按关键词检索（ThreadRecall 融合）后按 `seq · 类型 · 片段` 返回。
 */
package com.example.autop.agent.runtime.tools.control

import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.RuntimeStateReported
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadRecall
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.ToolSpec
import com.example.autop.agent.UserMessage
import com.example.autop.agent.toolSpec
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 历史检索工具。 */
internal class HistoryRecallTool(
    private val recall: ThreadRecall,
    private val prompts: PromptRepository,
) : Tool {
    override val id: String = "tool.history_recall"

    override val spec: ToolSpec =
        toolSpec(name = "history_recall", descriptionPromptId = "tool.history_recall") {
            integer("seq_from")
            integer("seq_to")
            string("query")
            integer("k")
            boolean("include_images")
        }

    override val access: ToolAccess = ToolAccess.READ_SYSTEM

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput {
        val query = args["query"]?.jsonPrimitive?.content.orEmpty()
        return if (query.isNotBlank()) queryMode(query, args, ctx) else rangeMode(args, ctx)
    }

    /** 关键词检索：每条 `seq · 类型 · 片段`，模型再按 seq 取原文。 */
    private suspend fun queryMode(
        query: String,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput {
        val cfg = ctx.config[AgentConfig.RECALL]
        val k = args["k"]?.jsonPrimitive?.intOrNull ?: cfg.defaultK
        val hits = recall.search(ctx.threadId, query, k)
        val text =
            hits
                .map { hit -> "${hit.seq} · ${typeOf(ctx, hit.seq)} · ${hit.snippet}" }
                .joinToString("\n")
        return ToolOutput(ok = true, content = listOf(Block.Text(text.ifEmpty { render(ID_NONE, emptyMap()) })))
    }

    /** seq 范围：事件原文，按 `maxChars` 分页截断。 */
    private suspend fun rangeMode(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput {
        val maxChars = ctx.config[AgentConfig.RECALL].maxChars
        val from = args["seq_from"]?.jsonPrimitive?.longOrNull ?: 0L
        val to = args["seq_to"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE
        val includeImages = args["include_images"]?.jsonPrimitive?.booleanOrNull ?: false
        val events = ctx.threadStore.events(ctx.threadId, from, to)
        return renderPage(events, includeImages, maxChars)
    }

    private fun renderPage(
        events: List<ThreadEvent>,
        includeImages: Boolean,
        maxChars: Int,
    ): ToolOutput {
        val blocks = mutableListOf<Block>()
        val text = StringBuilder()
        var lastSeq: Long? = null
        for (event in events) {
            val section = sectionOf(event)
            if (section != null) {
                if (text.length + section.text.length > maxChars) {
                    text.append(truncationTail(event.seq, lastSeq, section.text, maxChars))
                    break
                }
                text.append(section.text)
                lastSeq = event.seq
                if (includeImages) blocks += section.images
            }
        }
        return ToolOutput(ok = true, content = listOf(Block.Text(text.toString())) + blocks)
    }

    /**
     * 放不下 [text] 时的收尾文本：首条即超限给头部截断的原文并跳过该条（否则续看永远卡在同一条），
     * 否则续看从"放不下的这条"开始（seq_from 是闭区间，提示上一条会重复取）。
     */
    private fun truncationTail(
        seq: Long,
        lastSeq: Long?,
        text: String,
        maxChars: Int,
    ): String =
        if (lastSeq == null) {
            text.take(maxChars) + truncatedHint(seq + 1)
        } else {
            truncatedHint(seq)
        }

    private fun truncatedHint(nextSeq: Long): String = "\n" + render(ID_TRUNCATED, mapOf("next" to nextSeq.toString()))

    private fun render(
        id: String,
        vars: Map<String, String>,
    ): String =
        prompts
            .render(id, vars)
            .getOrNull()
            ?.text
            .orEmpty()

    private suspend fun typeOf(
        ctx: ToolContext,
        seq: Long,
    ): String {
        val event = ctx.threadStore.events(ctx.threadId, seq, seq).firstOrNull() ?: return TYPE_EVENT
        return typeOf(event)
    }

    private fun typeOf(event: ThreadEvent): String =
        when (event.payload) {
            is UserMessage -> TYPE_USER
            is AssistantMessage -> TYPE_ASSISTANT
            is ToolResultEvent -> TYPE_TOOL
            is RuntimeStateReported -> TYPE_RUNTIME_STATE
            else -> TYPE_EVENT
        }

    /** 事件的展示片段与图片块。 */
    private fun sectionOf(event: ThreadEvent): Section? {
        val blocks =
            when (val payload = event.payload) {
                is UserMessage -> payload.blocks
                is AssistantMessage -> payload.blocks
                is ToolResultEvent -> payload.blocks
                is RuntimeStateReported ->
                    return Section("#${event.seq} $TYPE_RUNTIME_STATE\n${payload.text}\n", emptyList())
                else -> return null
            }
        val text = blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
        val images = blocks.filterIsInstance<Block.Image>()
        return Section("#${event.seq} ${typeOf(event)}\n$text\n", images)
    }

    private class Section(
        val text: String,
        val images: List<Block.Image>,
    )

    private companion object {
        const val ID_NONE = "tool.history_recall.none"
        const val ID_TRUNCATED = "tool.history_recall.truncated"

        const val TYPE_USER = "user_message"
        const val TYPE_RUNTIME_STATE = "runtime_state"
        const val TYPE_ASSISTANT = "assistant_message"
        const val TYPE_TOOL = "tool_result"
        const val TYPE_EVENT = "event"
    }
}
