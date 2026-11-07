/**
 * `tool.update_plan`（docs/tech/04 §3.9）：全量更新任务计划，写 PlanUpdated 事件。
 */
package com.example.autop.agent.runtime.tools.control

import com.example.autop.agent.PlanItem
import com.example.autop.agent.PlanStatus
import com.example.autop.agent.PlanUpdated
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.agent.toolSpec
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 计划更新工具。 */
internal class UpdatePlanTool : Tool {
    override val id: String = "tool.update_plan"

    override val spec: ToolSpec =
        toolSpec(name = "update_plan", descriptionPromptId = "tool.update_plan") {
            array("items", required = true) {
                string("step", required = true)
                string("status", required = true, enum = listOf("pending", "doing", "done", "dropped"))
            }
            string("explanation")
        }

    override val access: ToolAccess = ToolAccess.CONTROL

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput {
        val items =
            args["items"]!!.jsonArray.map { el ->
                val one = el.jsonObject
                PlanItem(
                    step = one["step"]!!.jsonPrimitive.content,
                    status = statusOf(one["status"]!!.jsonPrimitive.content),
                )
            }
        ctx.threadStore.append(ctx.threadId, ctx.runId, PlanUpdated(items = items, explanation = explanationOf(args)))
        return ToolOutput(ok = true, content = listOf(Block.Text("计划已更新。")))
    }

    private fun statusOf(value: String): PlanStatus =
        when (value) {
            "pending" -> PlanStatus.PENDING
            "doing" -> PlanStatus.IN_PROGRESS
            "done" -> PlanStatus.DONE
            else -> PlanStatus.DROPPED
        }

    /** 本次更新的说明（空串按未提供处理）。 */
    private fun explanationOf(args: JsonObject): String? =
        args["explanation"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
