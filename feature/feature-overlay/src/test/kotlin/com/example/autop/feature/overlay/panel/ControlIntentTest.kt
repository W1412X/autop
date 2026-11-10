/**
 * [controlIntentSettled] 过渡态落地判定（悬浮层状态化 UI 纯逻辑）：暂停→PAUSED、
 * 继续→离开 PAUSED、中止→Run 消失/终态；active 换 Run 视为落地。
 */
package com.example.autop.feature.overlay.panel

import com.example.autop.agent.RunState
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ControlIntentTest {
    @Test
    fun `暂停落地于 PAUSED`() {
        assertThat(controlIntentSettled(intent(ControlIntentKind.PAUSE), active(RunStatus.RUNNING))).isFalse()
        assertThat(controlIntentSettled(intent(ControlIntentKind.PAUSE), active(RunStatus.PAUSED))).isTrue()
    }

    @Test
    fun `继续落地于离开 PAUSED`() {
        assertThat(controlIntentSettled(intent(ControlIntentKind.RESUME), active(RunStatus.PAUSED))).isFalse()
        assertThat(controlIntentSettled(intent(ControlIntentKind.RESUME), active(RunStatus.RUNNING))).isTrue()
    }

    @Test
    fun `中止落地于终态或消失`() {
        assertThat(controlIntentSettled(intent(ControlIntentKind.STOP), active(RunStatus.RUNNING))).isFalse()
        assertThat(controlIntentSettled(intent(ControlIntentKind.STOP), active(RunStatus.CANCELLED))).isTrue()
        assertThat(controlIntentSettled(intent(ControlIntentKind.STOP), null)).isTrue()
    }

    @Test
    fun `换 Run 即失效`() {
        val intent = intent(ControlIntentKind.STOP)
        val other = active(RunStatus.RUNNING, runId = "run-2")

        assertThat(controlIntentSettled(intent, other)).isTrue()
    }

    private fun intent(
        kind: ControlIntentKind,
        runId: String = "run-1",
    ): ControlIntent = ControlIntent(kind, RunId(runId))

    private fun active(
        status: RunStatus,
        runId: String = "run-1",
    ): ActiveRunInfo =
        ActiveRunInfo(
            run =
                RunRecord(
                    id = RunId(runId),
                    taskId = null,
                    threadId = ThreadId("thread-1"),
                    origin = RunOrigin.CHAT,
                    status = status,
                    triggerContext = emptyMap(),
                    createdAt = 0L,
                    startedAt = null,
                    endedAt = null,
                    summary = null,
                    errorCode = null,
                    pauseReason = null,
                    waitingQuestion = null,
                ),
            state = RunState.EXECUTING_TOOLS,
            currentStep = null,
            stepCount = 1,
            plan = emptyList(),
        )
}
