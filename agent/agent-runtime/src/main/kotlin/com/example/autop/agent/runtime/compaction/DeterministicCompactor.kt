/**
 * 确定性压缩（docs/tech/04 §3.6）：不调模型，Baseline = 预算内用户消息 +
 * 确定性说明（含计划与最近工具调用）+ 最后观测。
 */
package com.example.autop.agent.runtime.compaction

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.CompactionInput
import com.example.autop.agent.CompactionResult
import com.example.autop.agent.Compactor
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role

/** 确定性压缩策略（兜底，保证压缩链总有可用结果）。 */
internal class DeterministicCompactor(
    private val prompts: PromptRepository,
) : Compactor {
    override val id: String = "compaction.deterministic"

    private val assembler = BaselineAssembler(prompts)

    override suspend fun compact(input: CompactionInput): Outcome<CompactionResult> {
        val cfg = input.config[AgentConfig.COMPACTION]
        val (from, to) = assembler.rangeOf(input.live)
        val note =
            prompts
                .render(
                    ID_NOTE,
                    mapOf(
                        "fromSeq" to from.toString(),
                        "toSeq" to to.toString(),
                        "recentCalls" to assembler.recentCallsText(input.live, cfg.deterministicRecentCalls),
                    ),
                ).getOrNull()
                ?.text
                ?: return Outcome.Err(
                    AppError(code = AgentErrorCodes.COMPACTION_FAILED, message = "missing template $ID_NOTE"),
                )
        val plan = assembler.planText(input.plan)
        val middleText = if (plan.isBlank()) note else "$note\n\n$plan"
        return Outcome.Ok(
            CompactionResult(
                baselineMessages =
                    assembler.userMessages(input) +
                        Msg(Role.USER, listOf(Block.Text(middleText))) +
                        listOfNotNull(assembler.lastObservationMsg(input)),
                summaryText = null,
                coveredFromSeq = from,
                coveredToSeq = to,
            ),
        )
    }

    private companion object {
        const val ID_NOTE = "agent.compaction.deterministic_note"
    }
}
