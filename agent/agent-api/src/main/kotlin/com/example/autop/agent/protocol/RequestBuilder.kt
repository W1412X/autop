/**
 * 采样请求组装（docs/tech/04 §3.2/§3.8）：发送前先净化历史（[HistorySanitizer]）；
 * finalRound 不带工具；回退协议发送前降级历史；thinking 按模型能力与配置传 budget；
 * 缓存断点取最后一条 USER/TOOL 消息之前的那条消息下标。
 *
 * 放在 agent-api，智能体主循环与流程创作会话共用同一套组装逻辑。
 */
package com.example.autop.agent.protocol

import com.example.autop.core.config.AgentThinkingSection
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.LlmProtocols
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.SystemPart
import com.example.autop.llm.ToolChoice
import com.example.autop.llm.ToolSchema

/** 请求组装器（每次 Run 一个，绑定模型配置与净化占位文字）。 */
public class RequestBuilder(
    private val prompts: PromptRepository,
    private val profile: ModelProfile,
    private val thinking: AgentThinkingSection,
    private val texts: SanitizeTexts,
    /** 输出上限覆盖（流程/任务创作会话用固定上限；缺省取 [ModelProfile.maxOutputTokens]）。 */
    maxOutputTokensOverride: Int? = null,
) {
    private val fallback: Boolean = !profile.capabilities.tools
    private val maxOutputTokens: Int = maxOutputTokensOverride ?: profile.maxOutputTokens

    public fun build(
        system: List<SystemPart>,
        messages: List<Msg>,
        schemas: List<ToolSchema>,
        finalRound: Boolean,
    ): LlmRequest {
        val clean = HistorySanitizer.sanitize(messages, sanitizeTarget(), texts)
        val downgraded = if (fallback) JsonToolProtocol.downgrade(clean, prompts) else clean
        val tools = if (finalRound || fallback) emptyList() else schemas
        return LlmRequest(
            system = system,
            messages = downgraded,
            tools = tools,
            toolChoice = if (tools.isEmpty()) ToolChoice.NONE else ToolChoice.AUTO,
            maxOutputTokens = maxOutputTokens,
            thinkingBudgetTokens =
                if (profile.capabilities.thinking &&
                    thinking.enabled
                ) {
                    thinking.budgetTokens
                } else {
                    null
                },
            cacheBreakpointMessageIndex = cacheBreakpointOf(downgraded),
        )
    }

    private fun sanitizeTarget(): SanitizeTarget {
        val anthropic = profile.protocol == LlmProtocols.ANTHROPIC
        return SanitizeTarget(
            profileId = profile.id,
            keepThinking =
                if (anthropic) {
                    profile.capabilities.thinking && thinking.enabled
                } else {
                    profile.quirks[QUIRK_ECHO_REASONING] == "true"
                },
            vision = profile.capabilities.vision,
            requireThinkingSignature = anthropic,
        )
    }

    private fun cacheBreakpointOf(messages: List<Msg>): Int? {
        val last = messages.indexOfLast { it.role != Role.ASSISTANT }
        return (last - 1).takeIf { it > 0 }
    }

    private companion object {
        /** OpenAI 协议回显思考内容的 quirk（`OpenAiQuirkKeys.ECHO_REASONING`）。 */
        const val QUIRK_ECHO_REASONING = "echoReasoning"
    }
}
