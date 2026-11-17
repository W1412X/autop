package com.example.autop.feature.overlay.progress

import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunState
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.thread.ui.TimelineEntry
import com.example.autop.core.thread.ui.TimelineItem
import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.overlay.R
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 进度窗的状态推导（头部规格与结束元信息）；消息流归并与渲染由 core-thread-ui 的测试覆盖（ADR-0039）。 */
class ProgressLogicTest {
    @Test
    fun `header shows waiting hint while waiting for answer`() {
        val spec =
            progressHeaderSpec(
                activeRun(status = RunStatus.WAITING_USER, waitingQuestion = "打开哪个应用"),
                finished = null,
            )
        assertThat(spec.tone).isEqualTo(StatusTone.WAITING)
        assertThat(spec.chipText).isEqualTo(R.string.panel_status_waiting)
        assertThat(spec.hint).isEqualTo(R.string.progress_hint_waiting)
        assertThat(spec.runControls).isTrue()
    }

    @Test
    fun `header shows paused hint and blocked hint from pause reason`() {
        val paused = progressHeaderSpec(activeRun(status = RunStatus.PAUSED), finished = null)
        assertThat(paused.tone).isEqualTo(StatusTone.PAUSED)
        assertThat(paused.hint).isEqualTo(R.string.progress_hint_paused)
        val blocked = progressHeaderSpec(activeRun(pauseReason = PauseReason.POLICY_BLOCKED), finished = null)
        assertThat(blocked.tone).isEqualTo(StatusTone.BLOCKED)
        assertThat(blocked.chipText).isEqualTo(R.string.panel_status_blocked)
        assertThat(blocked.hint).isEqualTo(R.string.progress_hint_blocked)
    }

    @Test
    fun `header keeps run controls and has no hint while running`() {
        val spec = progressHeaderSpec(activeRun(), finished = null)
        assertThat(spec.tone).isEqualTo(StatusTone.RUNNING)
        assertThat(spec.hint).isNull()
        assertThat(spec.runControls).isTrue()
    }

    @Test
    fun `header collapses run controls and shows outcome after finish`() {
        val spec = progressHeaderSpec(active = null, finished = meta(RunOutcome.FAILED))
        assertThat(spec.tone).isEqualTo(StatusTone.BLOCKED)
        assertThat(spec.chipText).isEqualTo(R.string.progress_outcome_failed)
        assertThat(spec.runControls).isFalse()
    }

    @Test
    fun `header is queued neutral when nothing active or finished`() {
        val spec = progressHeaderSpec(active = null, finished = null)
        assertThat(spec.tone).isEqualTo(StatusTone.NEUTRAL)
        assertThat(spec.chipText).isEqualTo(R.string.panel_status_queued)
        assertThat(spec.runControls).isFalse()
    }

    @Test
    fun `face mood follows live run state`() {
        assertThat(progressFaceMoodOf(activeRun(), finished = null)).isEqualTo(BallFaceMood.RUNNING)
        assertThat(
            progressFaceMoodOf(activeRun(status = RunStatus.WAITING_USER), finished = null),
        ).isEqualTo(BallFaceMood.WAITING)
        assertThat(
            progressFaceMoodOf(activeRun(status = RunStatus.PAUSED), finished = null),
        ).isEqualTo(BallFaceMood.PAUSED)
        assertThat(
            progressFaceMoodOf(activeRun(pauseReason = PauseReason.POLICY_BLOCKED), finished = null),
        ).isEqualTo(BallFaceMood.BLOCKED)
        assertThat(progressFaceMoodOf(active = null, finished = null)).isEqualTo(BallFaceMood.IDLE)
    }

    @Test
    fun `face mood follows finished outcome`() {
        assertThat(
            progressFaceMoodOf(active = null, finished = meta(RunOutcome.SUCCEEDED)),
        ).isEqualTo(BallFaceMood.SUCCESS)
        assertThat(
            progressFaceMoodOf(active = null, finished = meta(RunOutcome.FAILED)),
        ).isEqualTo(BallFaceMood.FAILURE)
        assertThat(
            progressFaceMoodOf(active = null, finished = meta(RunOutcome.INTERRUPTED)),
        ).isEqualTo(BallFaceMood.PAUSED)
        assertThat(
            progressFaceMoodOf(active = null, finished = meta(RunOutcome.CANCELLED)),
        ).isEqualTo(BallFaceMood.IDLE)
    }

    @Test
    fun `finished meta is the standalone run result line`() {
        val entries = listOf(entry(TimelineItem.RunMeta(RunOutcome.SUCCEEDED, "搞定", 2, 1_000, 5)))
        assertThat(finishedMetaOf(entries)?.summary).isEqualTo("搞定")
    }

    @Test
    fun `finished meta attached to the trailing assistant text is found`() {
        val entries =
            listOf(
                entry(TimelineItem.UserBubble("打开设置", isSteer = false)),
                entry(
                    TimelineItem.AssistantText(
                        text = "好的",
                        streaming = false,
                        meta = meta(RunOutcome.SUCCEEDED),
                    ),
                ),
            )
        assertThat(finishedMetaOf(entries)?.outcome).isEqualTo(RunOutcome.SUCCEEDED)
    }

    @Test
    fun `finished meta is null while the run is still going`() {
        val entries = listOf(entry(TimelineItem.UserBubble("做个计划", isSteer = false)))
        assertThat(finishedMetaOf(entries)).isNull()
    }

    private fun meta(outcome: RunOutcome): TimelineItem.RunMeta =
        TimelineItem.RunMeta(outcome = outcome, summary = null, stepCount = 3, durationMs = 1_000, outputTokens = 5)

    private fun entry(item: TimelineItem): TimelineEntry = TimelineEntry(item = item, seq = null, forkable = false)

    private fun activeRun(
        status: RunStatus = RunStatus.RUNNING,
        pauseReason: PauseReason? = null,
        waitingQuestion: String? = null,
    ): ActiveRunInfo {
        val record =
            RunRecord(
                id = RunId("run-1"),
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
                pauseReason = pauseReason,
                waitingQuestion = waitingQuestion,
            )
        return ActiveRunInfo(record, RunState.SAMPLING, "正在执行", 3, emptyList())
    }
}
