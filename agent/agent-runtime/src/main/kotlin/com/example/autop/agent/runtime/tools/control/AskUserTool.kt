/**
 * `tool.ask_user`（docs/tech/04 §3.9）：向用户提问并等待回答；超时返回 agent.ask_timeout。
 */
package com.example.autop.agent.runtime.tools.control

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.agent.toolSpec
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** 提问工具。 */
internal class AskUserTool(
    private val prompts: PromptRepository,
) : Tool {
    override val id: String = "tool.ask_user"

    override val spec: ToolSpec =
        toolSpec(name = "ask_user", descriptionPromptId = "tool.ask_user") {
            string("question", required = true)
            array("options")
            integer("timeout_s", min = 1)
        }

    override val access: ToolAccess = ToolAccess.CONTROL

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput {
        val question =
            args["question"]
                ?.jsonPrimitive
                ?.content
                .orEmpty()
                .trim()
        if (question.isEmpty()) return blankQuestion()
        val options = args["options"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        val timeoutMs = timeoutMs(ctx, args)
        return when (val answer = ctx.askUser(question, options, timeoutMs)) {
            is Outcome.Ok -> ToolOutput(ok = true, content = listOf(Block.Text(answer.value)))
            // 只有真超时才按 ask_timeout 提示"继续或结束"；其余错误（如自测无交互端口）原样透出。
            is Outcome.Err ->
                if (answer.error.code == AgentErrorCodes.ASK_TIMEOUT) timeoutError() else errorOutput(answer.error)
        }
    }

    private fun timeoutMs(
        ctx: ToolContext,
        args: JsonObject,
    ): Long {
        val cfg = ctx.config[AgentConfig.TOOLS]
        val budget = cfg.timeoutMs[id] ?: cfg.defaultTimeoutMs
        // 提问超时必须先于执行器 withTimeout 到期，否则被打成 agent.tool_timeout 而非 ask_timeout（N172），
        // 不传 timeout_s 的默认路径同样留余量；预算本就很小（≤ 余量）时不强扣。
        val maxMs = if (budget > TIMEOUT_MARGIN_MS) budget - TIMEOUT_MARGIN_MS else budget
        args["timeout_s"]?.jsonPrimitive?.intOrNull?.let { return (it * MS_PER_SECOND).coerceIn(0, maxMs) }
        return maxMs
    }

    /** 非超时错误透出；正文由执行器统一成 error_result 格式（WP7.1）。 */
    private fun errorOutput(error: AppError): ToolOutput = ToolOutput(ok = false, content = emptyList(), error = error)

    /** 空问题不再弹窗（N172）。 */
    private fun blankQuestion(): ToolOutput =
        ToolOutput(
            ok = false,
            content = listOf(Block.Text("question 不能为空\n建议：question 需给出具体问题")),
            error = AppError(code = AgentErrorCodes.INVALID_ARGS, message = "question 不能为空", hint = "question 需给出具体问题"),
        )

    /** 超时按“继续或结束”提示模型（docs/tech/04 §3.9）。 */
    private fun timeoutError(): ToolOutput {
        val text =
            prompts
                .render(
                    "agent.tool.error_result",
                    mapOf(
                        "code" to AgentErrorCodes.ASK_TIMEOUT,
                        "message" to "user did not answer in time",
                        "hint" to HINT,
                    ),
                ).getOrNull()
                ?.text
                ?: ""
        return ToolOutput(
            ok = false,
            content = listOf(Block.Text(text)),
            error = AppError(code = AgentErrorCodes.ASK_TIMEOUT, message = "user did not answer in time", hint = HINT),
        )
    }

    private companion object {
        const val HINT = "按默认继续或结束"
        const val MS_PER_SECOND = 1000L
        const val TIMEOUT_MARGIN_MS = 5_000L
    }
}
