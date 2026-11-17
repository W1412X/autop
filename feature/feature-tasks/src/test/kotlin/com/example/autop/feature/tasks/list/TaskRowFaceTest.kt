package com.example.autop.feature.tasks.list

import com.example.autop.agent.PauseReason
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 任务行前导表情映射（P1-10）：只有未收口 Run 才换脸。 */
class TaskRowFaceTest {
    @Test
    fun `已结束或没有 Run 保留图标`() {
        assertThat(taskRowFaceOf(null)).isNull()
        assertThat(taskRowFaceOf(record(RunStatus.SUCCEEDED))).isNull()
        assertThat(taskRowFaceOf(record(RunStatus.FAILED))).isNull()
    }

    @Test
    fun `运行中显示运行脸`() {
        assertThat(taskRowFaceOf(record(RunStatus.RUNNING))).isEqualTo(BallFaceMood.RUNNING)
    }

    @Test
    fun `等待回答与暂停拦截各用自己的表情`() {
        assertThat(taskRowFaceOf(record(RunStatus.WAITING_USER))).isEqualTo(BallFaceMood.WAITING)
        assertThat(taskRowFaceOf(record(RunStatus.WAITING_SCREEN))).isEqualTo(BallFaceMood.WAITING)
        assertThat(taskRowFaceOf(record(RunStatus.PAUSED, pauseReason = PauseReason.MANUAL)))
            .isEqualTo(BallFaceMood.PAUSED)
        assertThat(taskRowFaceOf(record(RunStatus.PAUSED, pauseReason = PauseReason.POLICY_BLOCKED)))
            .isEqualTo(BallFaceMood.BLOCKED)
    }

    private fun record(
        status: RunStatus,
        pauseReason: PauseReason? = null,
    ): RunRecord =
        RunRecord(
            id = RunId("run-1"),
            taskId = null,
            threadId = ThreadId("thread-1"),
            origin = RunOrigin.TASK_MANUAL,
            status = status,
            triggerContext = emptyMap(),
            createdAt = 0L,
            startedAt = null,
            endedAt = null,
            summary = null,
            errorCode = null,
            pauseReason = pauseReason,
            waitingQuestion = null,
            waitingOptions = emptyList(),
        )
}
