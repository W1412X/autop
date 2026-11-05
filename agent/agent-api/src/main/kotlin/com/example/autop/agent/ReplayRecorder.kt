/**
 * 回放脚本录制（docs/tech/04 §3.12，R1/A6）：从一次成功的 Run 提取可回放动作序列。
 * 纯函数；放在 agent-api 是因为录制由 task-impl 在 Run 收口时调用（04 §3.12 末段），
 * 而 task-impl 只依赖 agent-api。
 */
package com.example.autop.agent

import com.example.autop.policy.PolicyErrorCodes

/** 录制器。 */
public object ReplayRecorder {
    /**
     * 取一次 `RunFinished(SUCCEEDED)` 的 Run 中所有成功的可回放动作，按 seq 排序。
     * 出现 ask_user、策略拦截、暂停、steer，或步数为 0 / 超过 [maxSteps] 时返回 null
     *（这次运行不适合录制）。
     */
    public fun build(
        events: List<ThreadEvent>,
        maxSteps: Int,
    ): ReplayScript? {
        val sorted = events.sortedBy { it.seq }
        val steps = sorted.mapNotNull { (it.payload as? ToolResultEvent)?.takeIf { r -> r.ok }?.replay }
        val runId = sorted.firstNotNullOfOrNull { it.runId }
        val recordable =
            sorted.any { (it.payload as? RunFinished)?.status == RunOutcome.SUCCEEDED } &&
                sorted.none(::disqualifies) &&
                steps.isNotEmpty() &&
                steps.size <= maxSteps
        return if (recordable && runId != null) ReplayScript(sourceRunId = runId, steps = steps) else null
    }

    /** 是否出现“不适合录制”的事件。 */
    private fun disqualifies(event: ThreadEvent): Boolean =
        when (val payload = event.payload) {
            is ToolResultEvent ->
                payload.toolId == ASK_USER_TOOL || payload.errorCode == PolicyErrorCodes.POLICY_BLOCKED
            is UserMessage -> payload.kind == UserMessageKind.STEER
            is RunPaused -> true
            else -> false
        }

    private const val ASK_USER_TOOL = "tool.ask_user"
}
