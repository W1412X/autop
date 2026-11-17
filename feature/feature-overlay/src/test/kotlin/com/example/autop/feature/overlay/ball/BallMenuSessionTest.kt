package com.example.autop.feature.overlay.ball

import com.example.autop.agent.PauseReason
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BallMenuSessionTest {
    @Test
    fun `session status follows the most urgent condition`() {
        assertThat(menuSessionStatusOf(run("r1", RunStatus.RUNNING, pauseReason = PauseReason.POLICY_BLOCKED)))
            .isEqualTo(MenuSessionStatus.BLOCKED)
        assertThat(menuSessionStatusOf(run("r2", RunStatus.RUNNING, question = "继续吗？")))
            .isEqualTo(MenuSessionStatus.WAITING)
        assertThat(menuSessionStatusOf(run("r3", RunStatus.WAITING_USER))).isEqualTo(MenuSessionStatus.WAITING)
        assertThat(menuSessionStatusOf(run("r4", RunStatus.PAUSED, pauseReason = PauseReason.MANUAL)))
            .isEqualTo(MenuSessionStatus.PAUSED)
        assertThat(menuSessionStatusOf(run("r5", RunStatus.RUNNING))).isEqualTo(MenuSessionStatus.RUNNING)
    }

    @Test
    fun `sessions sort by urgency and keep their titles`() {
        val openRuns =
            linkedMapOf(
                ThreadId("t-running") to run("r-running", RunStatus.RUNNING),
                ThreadId("t-waiting") to run("r-waiting", RunStatus.RUNNING, question = "先删哪个？"),
                ThreadId("t-paused") to run("r-paused", RunStatus.PAUSED, pauseReason = PauseReason.MANUAL),
            )
        val sessions =
            runBlocking {
                menuSessionsOf(openRuns) { id -> if (id.value == "t-waiting") "给张三回消息" else null }
            }
        assertThat(sessions.map { it.threadId.value }).containsExactly("t-waiting", "t-paused", "t-running").inOrder()
        assertThat(sessions.first().title).isEqualTo("给张三回消息")
        assertThat(sessions.first().mood).isEqualTo(com.example.autop.core.ui.component.ballface.BallFaceMood.WAITING)
    }

    private fun run(
        id: String,
        status: RunStatus,
        pauseReason: PauseReason? = null,
        question: String? = null,
    ): RunRecord =
        RunRecord(
            id = RunId(id),
            taskId = null,
            threadId = ThreadId("thread-$id"),
            origin = RunOrigin.CHAT,
            status = status,
            triggerContext = emptyMap(),
            createdAt = 0L,
            startedAt = null,
            endedAt = null,
            summary = null,
            errorCode = null,
            pauseReason = pauseReason,
            waitingQuestion = question,
            waitingOptions = emptyList(),
        )
}
