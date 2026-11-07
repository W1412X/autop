/**
 * 采样请求组装（docs/tech/04 §3.2/§3.8）：finalRound 不带工具；
 * 回退协议发送前降级历史；thinking 按模型能力与配置传 budget；缓存断点取最后一条
 * USER/TOOL 消息之前的那条消息下标。
 *
 * 放在 agent-api，智能体主循环与流程创作会话共用同一套组装逻辑。
 */
package com.example.autop.agent.protocol

import com.example.autop.core.config.AgentThinkingSection
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.SystemPart
import com.example.autop.llm.ToolChoice
import com.example.autop.llm.ToolSchema

/** 请求组装器（每次 Run 一个，绑定模型输出上限）。 */
public class RequestBuilder(
    private val prompts: PromptRepository,
    private val fallback: Boolean,
    private val thinking: AgentThinkingSection,
    private val thinkingCapable: Boolean,
    private val maxOutputTokens: Int,
) {
    public fun build(
        system: List<SystemPart>,
        messages: List<Msg>,
        schemas: List<ToolSchema>,
        finalRound: Boolean,
    ): LlmRequest {
        val downgraded = if (fallback) JsonToolProtocol.downgrade(messages, prompts) else messages
        val tools = if (finalRound || fallback) emptyList() else schemas
        return LlmRequest(
            system = system,
            messages = downgraded,
            tools = tools,
            toolChoice = if (tools.isEmpty()) ToolChoice.NONE else ToolChoice.AUTO,
            maxOutputTokens = maxOutputTokens,
            thinkingBudgetTokens = if (thinkingCapable && thinking.enabled) thinking.budgetTokens else null,
            cacheBreakpointMessageIndex = cacheBreakpointOf(downgraded),
        )
    }

    private fun cacheBreakpointOf(messages: List<Msg>): Int? {
        val last = messages.indexOfLast { it.role != Role.ASSISTANT }
        return (last - 1).takeIf { it > 0 }
    }
}
