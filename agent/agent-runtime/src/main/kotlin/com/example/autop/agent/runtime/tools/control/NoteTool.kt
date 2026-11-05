/**
 * `tool.note`（docs/tech/04 §3.9，R1/B3）：记一条本任务内的运行笔记，写 RunNoteAdded 事件。
 */
package com.example.autop.agent.runtime.tools.control

import com.example.autop.agent.RunNoteAdded
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.agent.toolSpec
import com.example.autop.core.config.AgentConfig
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 运行笔记工具。 */
internal class NoteTool : Tool {
    override val id: String = "tool.note"

    override val spec: ToolSpec =
        toolSpec(name = "note", descriptionPromptId = "tool.note") {
            string("text", required = true)
        }

    override val access: ToolAccess = ToolAccess.CONTROL

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput {
        val maxChars = ctx.config[AgentConfig.NOTES].maxCharsPerNote
        val text = args["text"]!!.jsonPrimitive.content.take(maxChars)
        ctx.threadStore.append(ctx.threadId, ctx.runId, RunNoteAdded(text = text))
        return ToolOutput(ok = true, content = listOf(Block.Text("已记下。")))
    }
}
