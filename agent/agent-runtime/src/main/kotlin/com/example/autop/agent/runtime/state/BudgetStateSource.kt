/**
 * 运行状态来源 `state.budget`（agent-harness-overhaul WP3.5.5）：预算余量与告急提示。
 * Run 内构造，持有本 Run 的 [Budget]。
 */
package com.example.autop.agent.runtime.state

import com.example.autop.agent.RuntimeStateSource
import com.example.autop.agent.StateEntry
import com.example.autop.agent.StateRequest
import com.example.autop.agent.runtime.guard.Budget
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.prompt.PromptRepository

/** 预算状态来源。 */
internal class BudgetStateSource(
    private val budget: Budget,
    private val prompts: PromptRepository,
) : RuntimeStateSource {
    override val id: String = ID

    override suspend fun entries(req: StateRequest): List<StateEntry> {
        val cfg = req.config[AgentConfig.BUDGET]
        val usage = budget.usage()
        val dims =
            listOfNotNull(
                dimOf(Budget.DIM_STEPS, usage.steps.toLong(), cfg.maxSteps.toLong(), cfg.maxSteps > 0),
                dimOf(Budget.DIM_TOKENS, usage.tokens, cfg.maxTotalTokens, cfg.maxTotalTokens > 0),
                dimOf(
                    Budget.DIM_DURATION,
                    usage.durationMs / MS_PER_SECOND,
                    cfg.maxDurationMs / MS_PER_SECOND,
                    cfg.maxDurationMs > 0,
                ),
                dimOf(
                    Budget.DIM_SCREENSHOTS,
                    usage.screenshots.toLong(),
                    cfg.maxScreenshots.toLong(),
                    cfg.maxScreenshots > 0,
                ),
            )
        if (dims.isEmpty()) return emptyList()
        val entries = mutableListOf<StateEntry>()
        entries +=
            StateEntry(
                KEY_REMAINING,
                render(PROMPT_REMAINING, mapOf(VAR_ITEMS to dims.joinToString("；") { it.text })),
                volatile = true,
            )
        val lowRatio = req.config[AgentConfig.STATE].budgetLowRatio
        val lowDims = dims.filter { it.limit > 0 && it.left.toDouble() / it.limit <= lowRatio }
        if (lowDims.isNotEmpty()) {
            entries +=
                StateEntry(
                    KEY_LOW,
                    render(PROMPT_LOW, mapOf(VAR_DIMS to lowDims.joinToString("、") { it.name })),
                )
        }
        return entries
    }

    /** 一个限额维度：已配置正数上限才产出；[limit] 为 0 = 不限。 */
    private fun dimOf(
        key: String,
        used: Long,
        limit: Long,
        enabled: Boolean,
    ): Dim? {
        if (!enabled) return null
        val left = (limit - used).coerceAtLeast(0)
        val text =
            render(
                PROMPT_DIM_PREFIX + key,
                mapOf(VAR_LEFT to left.toString(), VAR_LIMIT to limit.toString()),
            )
        return Dim(name = render(NAME_PREFIX + key), text = text, left = left, limit = limit)
    }

    private fun render(
        id: String,
        vars: Map<String, String> = emptyMap(),
    ): String =
        prompts
            .render(id, vars)
            .getOrNull()
            ?.text
            .orEmpty()

    private data class Dim(
        val name: String,
        val text: String,
        val left: Long,
        val limit: Long,
    )

    internal companion object {
        const val ID: String = "state.budget"

        const val KEY_REMAINING = "remaining"
        const val KEY_LOW = "low"

        private const val PROMPT_REMAINING = "agent.state.budget.remaining"
        private const val PROMPT_LOW = "agent.state.budget.low"
        private const val PROMPT_DIM_PREFIX = "agent.state.budget.dim."
        private const val NAME_PREFIX = "agent.budget.dimension."

        private const val VAR_ITEMS = "items"
        private const val VAR_DIMS = "dims"
        private const val VAR_LEFT = "left"
        private const val VAR_LIMIT = "limit"

        private const val MS_PER_SECOND = 1000L
    }
}
