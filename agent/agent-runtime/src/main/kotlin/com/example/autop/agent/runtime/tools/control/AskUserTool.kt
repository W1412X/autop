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
            integer("timeout_s")
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
            is Outcome.Err -> timeoutError()
        }
    }

    private fun timeoutMs(
        ctx: ToolContext,
        args: JsonObject,
    ): Long {
        val cfg = ctx.config[AgentConfig.TOOLS]
        val budget = cfg.timeoutMs[id] ?: cfg.defaultTimeoutMs
        val maxMs = (budget - TIMEOUT_MARGIN_MS).coerceAtLeast(0)
        // timeout_s 截断到执行器预算内，否则被 withTimeout 打成 agent.tool_timeout 而非 ask_timeout（N172）。
        args["timeout_s"]?.jsonPrimitive?.intOrNull?.let { return (it * MS_PER_SECOND).coerceIn(0, maxMs) }
        return budget
    }

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
