package com.example.autop.flow.impl.ports

import com.example.autop.core.common.Clock
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.FlowConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.core.LlmCall
import com.example.autop.flow.core.LlmPort
import com.example.autop.flow.core.LlmReply
import com.example.autop.flow.core.ObservePort
import com.example.autop.flow.core.expr.FlowException
import com.example.autop.llm.Block
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.SystemPart
import com.example.autop.llm.ToolChoice
import com.example.autop.llm.complete

/** LLM 端口：使用默认模型，渲染系统提示模板，返回文本与用量。 */
internal class LlmPortImpl(
    private val gateway: LlmGateway,
    private val prompts: PromptRepository,
    private val images: FlowImages,
    private val observe: ObservePort,
    private val configProvider: ConfigProvider,
    private val usedTokensToday: suspend () -> Long,
    private val clock: Clock,
) : LlmPort {
    override suspend fun complete(call: LlmCall): LlmReply {
        checkDailyBudget()
        val provider = defaultProvider()
        val blocks = mutableListOf<Block>(Block.Text(userText(call)))
        call.images.forEach { ref ->
            blocks += images.get(ref) ?: throw FlowException(FlowErrorCodes.ARGS_INVALID, "找不到图片引用：$ref")
        }
        if (call.attachScreenshot) {
            val ref = observe.screenshot(null)
            images.get(ref)?.let { blocks += it }
        }
        val request =
            LlmRequest(
                system = listOf(SystemPart(systemText(call))),
                messages = listOf(Msg(Role.USER, blocks)),
                toolChoice = ToolChoice.NONE,
                maxOutputTokens = call.maxOutputTokens ?: configProvider.current()[FlowConfig.LLM].maxOutputTokens,
                temperature = call.temperature,
            )
        return when (val result = provider.complete(request)) {
            is Outcome.Err -> throw FlowException(result.error.code, result.error.message)
            is Outcome.Ok -> {
                val completed = result.value
                LlmReply(
                    text =
                        completed.message.blocks
                            .filterIsInstance<Block.Text>()
                            .joinToString("") { it.text },
                    promptTokens = completed.usage?.inputTokens?.toLong() ?: 0,
                    completionTokens = completed.usage?.outputTokens?.toLong() ?: 0,
                )
            }
        }
    }

    private suspend fun checkDailyBudget() {
        val budget = configProvider.current()[FlowConfig.LLM].dailyTokenBudget
        if (budget > 0 && usedTokensToday() >= budget) {
            throw FlowException(FlowErrorCodes.BUDGET_EXCEEDED, "已超过今日 LLM token 预算 $budget")
        }
        clock.nowMillis()
    }

    private fun defaultProvider(): LlmProvider =
        when (val r = gateway.forDefault()) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> throw FlowException(FlowErrorCodes.LLM_UNAVAILABLE, r.error.message)
        }

    private fun systemText(call: LlmCall): String {
        val parts = mutableListOf(render(SYSTEM, emptyMap()))
        call.system?.takeIf { it.isNotBlank() }?.let { parts += it }
        call.jsonSchema?.let { parts += render(JSON_ONLY, mapOf("schema" to it.toString())) }
        return parts.joinToString("\n\n")
    }

    private fun userText(call: LlmCall): String {
        val sb = StringBuilder(call.prompt)
        call.screenText?.let { sb.append("\n\n").append(render(SCREEN_SECTION, mapOf("screen" to it))) }
        if (call.feedback != null) {
            sb.append("\n\n").append(
                render(
                    RETRY_FEEDBACK,
                    mapOf(
                        "problem" to call.feedback.orEmpty(),
                        "previous" to call.previousOutput.orEmpty(),
                    ),
                ),
            )
        }
        return sb.toString()
    }

    private fun render(
        id: String,
        vars: Map<String, String>,
    ): String =
        when (val r = prompts.render(id, vars)) {
            is Outcome.Ok -> r.value.text
            is Outcome.Err -> throw FlowException(FlowErrorCodes.INVALID, "提示词模板渲染失败：$id")
        }

    private companion object {
        const val SYSTEM = "flow.llm.system"
        const val JSON_ONLY = "flow.llm.json_only"
        const val RETRY_FEEDBACK = "flow.llm.retry_feedback"
        const val SCREEN_SECTION = "flow.llm.screen_section"
    }
}
