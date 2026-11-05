/**
 * `tool.finish`（docs/tech/04 §3.9）：显式交付结束，返回 control=Finish。
 */
package com.example.autop.agent.runtime.tools.control

import com.example.autop.agent.RunOutcome
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolControl
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.agent.toolSpec
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 结束工具。 */
internal class FinishTool : Tool {
    override val id: String = "tool.finish"

    override val spec: ToolSpec =
        toolSpec(name = "finish", descriptionPromptId = "tool.finish") {
            string("status", required = true, enum = listOf("success", "failed"))
            string("summary", required = true)
            anyObject("outputs")
        }

    override val access: ToolAccess = ToolAccess.CONTROL

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput {
        val succeeded = args["status"]!!.jsonPrimitive.content == "success"
        val status = if (succeeded) RunOutcome.SUCCEEDED else RunOutcome.FAILED
        val summary = args["summary"]!!.jsonPrimitive.content
        return ToolOutput(
            ok = true,
            content = listOf(Block.Text("已交付结果。")),
            control = ToolControl.Finish(status = status, summary = summary, outputs = args["outputs"] as? JsonObject),
        )
    }
}
