/**
 * LIGHT 模型调用辅助（docs/tech/07 §6）：LIGHT 未配置时回退 MAIN；
 * 收集流取最终文本，供草稿生成与条件判定共用。
 */
package com.example.autop.task.impl.draft

import com.example.autop.core.common.Outcome
import com.example.autop.llm.Block
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.ModelRole
import com.example.autop.llm.Msg
import com.example.autop.llm.ToolChoice
import com.example.autop.llm.complete

/** LIGHT 优先、MAIN 兜底的轻量补全。 */
internal object LlmCalls {
    /** 轻量补全的最大输出 tokens。 */
    const val MAX_OUTPUT_TOKENS: Int = 1024

    /** 单轮补全并返回拼接后的文本（LIGHT 未配置回退 MAIN）。 */
    suspend fun completeText(
        gateway: LlmGateway,
        messages: List<Msg>,
    ): Outcome<String> {
        val llm =
            when (val light = gateway.forRole(ModelRole.LIGHT)) {
                is Outcome.Ok -> light.value
                is Outcome.Err ->
                    when (val main = gateway.forRole(ModelRole.MAIN)) {
                        is Outcome.Ok -> main.value
                        is Outcome.Err -> return Outcome.Err(main.error)
                    }
            }
        val request =
            LlmRequest(
                system = emptyList(),
                messages = messages,
                tools = emptyList(),
                toolChoice = ToolChoice.NONE,
                maxOutputTokens = MAX_OUTPUT_TOKENS,
            )
        return when (val completed = llm.complete(request)) {
            is Outcome.Ok ->
                Outcome.Ok(
                    completed.value.message.blocks
                        .filterIsInstance<Block.Text>()
                        .joinToString("") { it.text },
                )
            is Outcome.Err -> Outcome.Err(completed.error)
        }
    }
}
