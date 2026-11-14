package com.example.autop.core.thread.ui

import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunPaused
import com.example.autop.agent.RunResumed
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.timeline.BannerKind
import com.example.autop.core.common.ThreadId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 暂停横幅的生命周期：恢复或运行结束后，未决的暂停/拦截横幅必须消失（含其按钮）。 */
class TimelineBannerTest {
    @Test
    fun `resume removes the pause banner`() {
        val entries =
            mergeTimeline(
                listOf(event(1, RunPaused(reason = PauseReason.MANUAL, detail = null)), event(2, RunResumed())),
                emptyList(),
                GROUP_THRESHOLD,
            )
        assertThat(banners(entries)).isEmpty()
    }

    @Test
    fun `finished run clears a stale pause banner`() {
        val entries =
            mergeTimeline(
                listOf(
                    event(1, RunPaused(reason = PauseReason.MANUAL, detail = null)),
                    event(2, RunFinished(status = RunOutcome.CANCELLED, summary = null)),
                ),
                emptyList(),
                GROUP_THRESHOLD,
            )
        assertThat(banners(entries)).isEmpty()
    }

    @Test
    fun `interrupted finish clears the pause banner`() {
        val entries =
            mergeTimeline(
                listOf(
                    event(1, RunPaused(reason = PauseReason.MANUAL, detail = null)),
                    event(2, RunFinished(status = RunOutcome.INTERRUPTED, summary = null)),
                ),
                emptyList(),
                GROUP_THRESHOLD,
            )
        assertThat(banners(entries)).doesNotContain(BannerKind.PAUSED)
    }

    private fun banners(entries: List<TimelineEntry>): List<BannerKind> =
        entries.mapNotNull { (it.item as? TimelineItem.Banner)?.kind }

    private fun event(
        seq: Long,
        payload: com.example.autop.agent.EventPayload,
    ): ThreadEvent =
        ThreadEvent(
            threadId = ThreadId("thread-banner"),
            seq = seq,
            runId = null,
            createdAt = seq * 1000,
            payload = payload,
        )

    private companion object {
        const val GROUP_THRESHOLD = 3
    }
}
