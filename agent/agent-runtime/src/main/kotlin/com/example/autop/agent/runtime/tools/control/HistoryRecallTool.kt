/**
 * `tool.history_recall`（docs/tech/04 §3.9）：按 seq 范围取事件原文（分页、单次不超过
 * `agent.recall.maxChars`），或按关键词检索（ThreadRecall 融合）后按 `seq · 类型 · 片段` 返回。
 */
package com.example.autop.agent.runtime.tools.control

import com.example.autop.agent.AssistantMessage
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
import com.example.autop.core.config.AgentConfig
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 历史检索工具。 */
internal class HistoryRecallTool(
    private val recall: ThreadRecall,
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
        return ToolOutput(ok = true, content = listOf(Block.Text(text.ifEmpty { "没有找到相关记录。" })))
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
                    text.append(truncatedHint(lastSeq ?: event.seq))
                    break
                }
                text.append(section.text)
                lastSeq = event.seq
                if (includeImages) blocks += section.images
            }
        }
        return ToolOutput(ok = true, content = listOf(Block.Text(text.toString())) + blocks)
    }

    private fun truncatedHint(nextSeq: Long): String = "\n…（已截断，可用 seq_from=$nextSeq 继续查看）"

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
            else -> TYPE_EVENT
        }

    /** 事件的展示片段与图片块。 */
    private fun sectionOf(event: ThreadEvent): Section? {
        val blocks =
            when (val payload = event.payload) {
                is UserMessage -> payload.blocks
                is AssistantMessage -> payload.blocks
                is ToolResultEvent -> payload.blocks
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
        const val TYPE_USER = "user_message"
        const val TYPE_ASSISTANT = "assistant_message"
        const val TYPE_TOOL = "tool_result"
        const val TYPE_EVENT = "event"
    }
}
