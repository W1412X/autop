/**
 * Run 状态映射（docs/tech/07 §1.2）：agent 状态机与 RunRecord 生命周期状态的换算。
 */
package com.example.autop.task.impl

import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunState
import com.example.autop.agent.runEndedNoteId
import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.task.RunStatus

/** 句柄状态 → 记录状态；FINISHED 由收口写终态，返回 null。 */
internal fun RunState.toRunStatus(): RunStatus? =
    when (this) {
        RunState.STARTING,
        RunState.SAMPLING,
        RunState.EXECUTING_TOOLS,
        RunState.COMPACTING,
        -> RunStatus.RUNNING
        RunState.PAUSED -> RunStatus.PAUSED
        RunState.WAITING_USER -> RunStatus.WAITING_USER
        RunState.FINISHED -> null
    }

/** 结束状态 → 记录终态。 */
internal fun RunOutcome.toRunStatus(): RunStatus =
    when (this) {
        RunOutcome.SUCCEEDED -> RunStatus.SUCCEEDED
        RunOutcome.FAILED -> RunStatus.FAILED
        RunOutcome.CANCELLED -> RunStatus.CANCELLED
        RunOutcome.BUDGET_EXHAUSTED -> RunStatus.BUDGET_EXHAUSTED
        RunOutcome.BREAKER_STOPPED -> RunStatus.BREAKER_STOPPED
        RunOutcome.INTERRUPTED -> RunStatus.INTERRUPTED
        RunOutcome.EXPIRED -> RunStatus.EXPIRED
    }

/** Run 结束 liveNote 文字（附录 A.9 模板；SUCCEEDED 不写，模板缺失给 null）。 */
internal suspend fun PromptRepository.runEndedNote(status: RunOutcome): String? =
    if (status == RunOutcome.SUCCEEDED) {
        null
    } else {
        render(runEndedNoteId(status), emptyMap()).getOrNull()?.text
    }
