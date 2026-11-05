/**
 * 观测降级规则 `history.observation_degrade`（docs/tech/04 §3.4）：
 * 含观测的结果从新到旧保留 `keepFull` 个原文，更早的替换为 stub。
 * R1/B1：保留窗口里最旧的一个若是差量，改为渲染其 `fullBlocks`（差量基准保全）。
 */
package com.example.autop.agent.runtime.rules

import com.example.autop.agent.HistoryRenderRule
import com.example.autop.agent.RenderItem
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block

/** 观测降级。 */
internal class ObservationDegradeRule(
    private val prompts: PromptRepository,
) : HistoryRenderRule {
    override val id: String = ID

    override fun apply(
        items: List<RenderItem>,
        config: ConfigSnapshot,
    ): List<RenderItem> {
        val keepFull = config[AgentConfig.HISTORY].observation.keepFull
        val obsIndices = items.indices.filter { items[it].observation != null }
        // N209：keepFull ≤ 0 不降级（全部保留原文；窗口最旧差量的基准保全仍生效）。
        val window = if (keepFull <= 0) obsIndices else obsIndices.takeLast(keepFull)
        val windowOldest = window.firstOrNull()
        return items.mapIndexed { i, item ->
            val obs = item.observation ?: return@mapIndexed item
            when {
                i !in window -> stub(item, obs.summaryLine, obs.nodeCount)
                i == windowOldest && obs.isDiff -> keepDiffBase(item)
                else -> item
            }
        }
    }

    /** 窗口外的观测换 stub（保留 Text/Image 结构外的原文都不再进上下文）。 */
    private fun stub(
        item: RenderItem,
        summary: String,
        nodeCount: Int,
    ): RenderItem {
        val text =
            prompts
                .render(
                    ID_STUB,
                    mapOf("seq" to item.seq.toString(), "summary" to summary, "nodeCount" to nodeCount.toString()),
                ).getOrNull()
                ?.text
                ?: return item
        return item.copy(msg = item.msg.withToolResultContent(listOf(Block.Text(text))))
    }

    /** 差量基准：优先换 fullBlocks；没有完整原文时保留差量并加提示（R1/B1）。 */
    private fun keepDiffBase(item: RenderItem): RenderItem {
        val full = item.fullBlocks
        if (full != null && full.isNotEmpty()) return item.copy(msg = item.msg.withToolResultContent(full))
        val marker =
            prompts
                .render(ID_DIFF_MISSING)
                .getOrNull()
                ?.text
                ?.let { Block.Text(it) }
        val original =
            item.msg
                .toolResult()
                ?.content
                .orEmpty()
        return if (marker == null) {
            item
        } else {
            item.copy(msg = item.msg.withToolResultContent(listOf(marker) + original))
        }
    }

    private companion object {
        const val ID = "history.observation_degrade"
        const val ID_STUB = "agent.history.observation_stub"
        const val ID_DIFF_MISSING = "agent.history.diff_base_missing"
    }
}
