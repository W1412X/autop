/**
 * 新 Baseline 消息的公共装配（docs/tech/04 §3.6 第 4 步）：
 * 预算内真实用户消息 + 中段（摘要/确定性说明）+ 最后一次观测 + 触发上下文/计划的确定性渲染。
 */
package com.example.autop.agent.runtime.compaction

import com.example.autop.agent.CompactionInput
import com.example.autop.agent.PlanItem
import com.example.autop.agent.RenderItem
import com.example.autop.agent.runtime.TokenEstimator
import com.example.autop.agent.runtime.planText
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.AgentTokensSection
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role

/** Baseline 消息装配器。 */
internal class BaselineAssembler(
    private val prompts: PromptRepository,
) {
    /**
     * 最近的真实用户消息（INPUT/STEER，不含 INJECTED）：从新到旧选取，
     * 总估算不超过 `keepUserMessageTokens`，超出的那条截断后停止，再恢复时间顺序。
     */
    fun userMessages(input: CompactionInput): List<Msg> {
        val cfg = input.config[AgentConfig.COMPACTION]
        val tokens = input.config[AgentConfig.TOKENS]
        val estimator = TokenEstimator(tokens)
        val selected = mutableListOf<Pair<Long, Msg>>()
        var used = 0
        for ((seq, msg) in input.userMessagesNewestFirst) {
            val cost = estimator.estimateMessage(msg)
            if (used + cost <= cfg.keepUserMessageTokens) {
                selected += seq to msg
                used += cost
            } else {
                val remaining = cfg.keepUserMessageTokens - used
                if (remaining > 0) selected += seq to truncate(msg, remaining, tokens)
                break
            }
        }
        return selected.sortedBy { it.first }.map { it.second }
    }

    /** 计划的确定性渲染（每行一项）。 */
    fun planText(items: List<PlanItem>): String = items.planText()

    /** 触发上下文（`trigger.` 前缀的 runVars）。 */
    fun triggerText(vars: Map<String, String>): String {
        val trigger = vars.filterKeys { it.startsWith(PREFIX) }
        if (trigger.isEmpty()) return ""
        return trigger.entries.joinToString("\n") { (key, value) -> "${key.removePrefix(PREFIX)}: $value" }
    }

    /** `agent.compaction.last_observation` 包装的最后观测（USER 消息；差量用 fullBlocks）。 */
    fun lastObservationMsg(input: CompactionInput): Msg? {
        val item = input.lastObservation ?: return null
        val content = item.fullBlocks ?: toolResultBlocks(item)
        val text = content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
        val images = content.filterIsInstance<Block.Image>()
        val rendered =
            prompts
                .render(ID_LAST_OBSERVATION, mapOf("observation" to text))
                .getOrNull()
                ?.text
        return rendered?.let { Msg(Role.USER, listOf(Block.Text(it)) + images) }
    }

    /** 最近工具调用的名称与结果首行列表（DeterministicCompactor 用）。 */
    fun recentCallsText(
        live: List<RenderItem>,
        max: Int,
    ): String =
        live
            .filter { it.toolId != null }
            .takeLast(max)
            .joinToString("\n") { "${it.toolId}: ${firstLineOf(it)}" }

    /** 覆盖区间：live 首尾 seq。 */
    fun rangeOf(live: List<RenderItem>): Pair<Long, Long> =
        (live.firstOrNull()?.seq ?: 0L) to (live.lastOrNull()?.seq ?: 0L)

    private fun truncate(
        msg: Msg,
        remainingTokens: Int,
        tokens: AgentTokensSection,
    ): Msg {
        val text = msg.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
        val chars = (remainingTokens * tokens.charsPerToken).toInt()
        return Msg(msg.role, listOf(Block.Text(text.take(chars))))
    }

    private fun toolResultBlocks(item: RenderItem): List<Block> =
        item.msg.blocks
            .filterIsInstance<Block.ToolResult>()
            .flatMap { it.content }

    private fun firstLineOf(item: RenderItem): String =
        toolResultBlocks(item)
            .filterIsInstance<Block.Text>()
            .joinToString("\n") { it.text }
            .lineSequence()
            .firstOrNull()
            .orEmpty()

    private companion object {
        const val PREFIX = "trigger."
        const val ID_LAST_OBSERVATION = "agent.compaction.last_observation"
    }
}
