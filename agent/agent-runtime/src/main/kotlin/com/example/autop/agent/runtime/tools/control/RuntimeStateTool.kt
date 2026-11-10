/**
 * `tool.get_device_state`（agent-harness-overhaul WP3.8）：返回当前完整运行状态，
 * 与运行状态消息同一份数据（来源见 [RuntimeStateTracker]）。
 */
package com.example.autop.agent.runtime.tools.control

import com.example.autop.agent.StateRequest
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.agent.runtime.state.RuntimeStateTracker
import com.example.autop.agent.toolSpec
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject

/** 运行状态查询工具。 */
internal class RuntimeStateTool : Tool {
    override val id: String = "tool.get_device_state"

    override val spec: ToolSpec =
        toolSpec(name = "get_device_state", descriptionPromptId = "tool.get_device_state") {}

    override val access: ToolAccess = ToolAccess.READ_SYSTEM

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput {
        val tracker = ctx.runScope.getOrPut(RuntimeStateTracker.SCOPE_KEY) { missingTracker() }
        val req = StateRequest(ctx.runId, ctx.threadId, ctx.config, emptyMap())
        return ToolOutput(ok = true, content = listOf(Block.Text(tracker.fullText(req))))
    }

    private fun missingTracker(): RuntimeStateTracker = error("runtime state tracker is not in runScope")
}
