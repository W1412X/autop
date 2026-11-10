/**
 * 工具错误结果工厂（docs/tech/04 §3.3）：错误结果的 content 统一为
 * `agent.tool.error_result`；从 ToolRouter 拆出的单职责类。
 */
package com.example.autop.agent.runtime.tools

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block

/** 错误结果工厂。 */
internal class ToolErrors(
    private val prompts: PromptRepository,
) {
    /** 错误结果的 content 统一为 `agent.tool.error_result`（docs/tech/04 §3.3）。 */
    fun errorOutput(
        code: String,
        message: String,
        hint: String? = null,
    ): ToolOutput {
        val text = render(ID_ERROR_RESULT, mapOf("code" to code, "message" to message, "hint" to (hint ?: "")))
        return ToolOutput(
            ok = false,
            content = listOf(Block.Text(text)),
            error = AppError(code = code, message = message, hint = hint),
        )
    }

    fun unknownTool(
        name: String,
        available: List<String>,
    ): ToolOutput =
        errorOutput(
            AgentErrorCodes.UNKNOWN_TOOL,
            "unknown tool '$name'",
            "available tools: ${available.sorted().joinToString()}",
        )

    fun invalidArgsJson(): ToolOutput = errorOutput(AgentErrorCodes.INVALID_ARGS_JSON, "arguments is not a JSON object")

    fun invalidArgs(errors: List<String>): ToolOutput =
        errorOutput(AgentErrorCodes.INVALID_ARGS, "invalid arguments", errors.joinToString("; "))

    /** 批次内被跳过的结果（WP5.2）。 */
    fun skipped(reason: SkipReason): ToolOutput =
        when (reason) {
            SkipReason.PREVIOUS_FAILED ->
                errorOutput(
                    AgentErrorCodes.SKIPPED_PREVIOUS_FAILED,
                    "skipped: an earlier write in this batch failed",
                    render(ID_SKIPPED_PREVIOUS_FAILED_HINT, emptyMap()),
                )
            SkipReason.AFTER_CONTROL ->
                errorOutput(
                    AgentErrorCodes.SKIPPED_AFTER_CONTROL,
                    "skipped: an earlier call in this batch ended or paused the run",
                    render(ID_SKIPPED_AFTER_CONTROL_HINT, emptyMap()),
                )
        }

    fun missingOutput(): ToolOutput = errorOutput(AgentErrorCodes.TOOL_CRASHED, "missing result")

    /** 渲染提示模板；缺失时返回空串。 */
    fun render(
        id: String,
        vars: Map<String, String>,
    ): String = prompts.render(id, vars).let { (it as? Outcome.Ok)?.value?.text ?: "" }

    private companion object {
        const val ID_ERROR_RESULT = "agent.tool.error_result"
        const val ID_SKIPPED_PREVIOUS_FAILED_HINT = "agent.tool.skipped_hint.previous_failed"
        const val ID_SKIPPED_AFTER_CONTROL_HINT = "agent.tool.skipped_hint.after_control"
    }
}
