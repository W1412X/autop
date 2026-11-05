/**
 * 压缩协调（docs/tech/04 §3.6）：按配置的策略链带重试执行，成功后写
 * `Baseline(COMPACTION)`；连续失败超过上限时 Run 应以 agent.compaction_failed 结束。
 */
package com.example.autop.agent.runtime.compaction

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.Baseline
import com.example.autop.agent.BaselineReason
import com.example.autop.agent.CompactionInput
import com.example.autop.agent.CompactionResult
import com.example.autop.agent.Compactor
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.runtime.notes.RunNotes
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.config.AgentConfig

/** 一次压缩的走向。 */
internal sealed interface CompactionOutcome {
    /** 成功：已写 Baseline。 */
    data class Done(
        val result: CompactionResult,
    ) : CompactionOutcome

    /** 本次失败但未超连续失败上限：不压缩继续跑。 */
    data object Tolerated : CompactionOutcome

    /** 连续失败超上限：Run 必须结束。 */
    data class Failed(
        val error: AppError,
    ) : CompactionOutcome
}

/** 压缩协调器（每次 Run 一个，携带连续失败计数）。 */
internal class CompactionCoordinator(
    private val store: ThreadStore,
    private val compactors: Set<Compactor>,
    private val notes: RunNotes,
    private val runId: RunId,
) {
    private val byId: Map<String, Compactor> = compactors.associateBy { it.id }
    private var consecutiveFailures = 0

    suspend fun compact(input: CompactionInput): CompactionOutcome {
        val cfg = input.config[AgentConfig.COMPACTION]
        for (strategyId in cfg.strategies) {
            val compactor = byId[strategyId] ?: continue
            repeat(1 + cfg.retries) {
                val result = compactor.compact(input)
                if (result is Outcome.Ok) return done(input, result.value)
            }
        }
        return failed(cfg.maxConsecutiveFailures)
    }

    private suspend fun done(
        input: CompactionInput,
        result: CompactionResult,
    ): CompactionOutcome {
        consecutiveFailures = 0
        store.append(
            input.threadId,
            runId,
            Baseline(
                reason = BaselineReason.COMPACTION,
                messages = result.baselineMessages,
                coveredFromSeq = result.coveredFromSeq,
                coveredToSeq = result.coveredToSeq,
                summaryText = result.summaryText,
                notes = notes.snapshot(input.threadId),
            ),
        )
        return CompactionOutcome.Done(result)
    }

    private fun failed(maxConsecutiveFailures: Int): CompactionOutcome {
        consecutiveFailures += 1
        return if (consecutiveFailures > maxConsecutiveFailures) {
            CompactionOutcome.Failed(
                AppError(
                    code = AgentErrorCodes.COMPACTION_FAILED,
                    message = "compaction failed $consecutiveFailures times in a row",
                ),
            )
        } else {
            CompactionOutcome.Tolerated
        }
    }
}
