package com.example.autop.feature.overlay.ball

import com.example.autop.agent.PauseReason
import com.example.autop.agent.PlanItem
import com.example.autop.agent.RunState
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BallLogicTest {
    private fun activeRun(
        status: RunStatus = RunStatus.RUNNING,
        pauseReason: PauseReason? = null,
        waitingQuestion: String? = null,
        stepCount: Int = 3,
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
        return ActiveRunInfo(record, RunState.SAMPLING, null, stepCount, emptyList<PlanItem>())
    }

    @Test
    fun `accessibility disconnected wins over running`() {
        val mode = deriveBallMode(activeRun(), accessibilityConnected = false, maxSteps = 40, toolCategory = null)
        assertThat(mode).isEqualTo(BallMode.AccessibilityLost)
    }

    @Test
    fun `no active run is idle`() {
        val mode = deriveBallMode(null, accessibilityConnected = true, maxSteps = 40, toolCategory = null)
        assertThat(mode).isEqualTo(BallMode.Idle)
    }

    @Test
    fun `policy blocked is blocked`() {
        val mode =
            deriveBallMode(
                activeRun(pauseReason = PauseReason.POLICY_BLOCKED),
                accessibilityConnected = true,
                maxSteps = 40,
                toolCategory = null,
            )
        assertThat(mode).isEqualTo(BallMode.Blocked)
    }

    @Test
    fun `waiting question is waiting answer`() {
        val mode =
            deriveBallMode(
                activeRun(waitingQuestion = "继续吗？"),
                accessibilityConnected = true,
                maxSteps = 40,
                toolCategory = null,
            )
        assertThat(mode).isEqualTo(BallMode.WaitingAnswer)
    }

    @Test
    fun `paused status is paused`() {
        val mode =
            deriveBallMode(
                activeRun(status = RunStatus.PAUSED, pauseReason = PauseReason.MANUAL),
                accessibilityConnected = true,
                maxSteps = 40,
                toolCategory = null,
            )
        assertThat(mode).isEqualTo(BallMode.Paused)
    }

    @Test
    fun `running carries progress and tool category`() {
        val mode =
            deriveBallMode(
                activeRun(stepCount = 10),
                accessibilityConnected = true,
                maxSteps = 40,
                toolCategory = ToolCategory.GESTURE,
            )
        assertThat(mode)
            .isEqualTo(BallMode.Running(stepCount = 10, progress = 0.25f, toolCategory = ToolCategory.GESTURE))
    }

    @Test
    fun `non positive budget yields null progress`() {
        assertThat(progressOf(stepCount = 5, maxSteps = 0)).isNull()
        assertThat(progressOf(stepCount = 50, maxSteps = 40)).isEqualTo(1f)
    }

    @Test
    fun `tool categories map by semantics`() {
        assertThat(categoryOfTool("tool.tap")).isEqualTo(ToolCategory.GESTURE)
        assertThat(categoryOfTool("tool.input_text")).isEqualTo(ToolCategory.TEXT)
        assertThat(categoryOfTool("tool.screenshot")).isEqualTo(ToolCategory.VISION)
        assertThat(categoryOfTool("tool.open_app")).isEqualTo(ToolCategory.APP)
        assertThat(categoryOfTool("tool.wait")).isEqualTo(ToolCategory.OTHER)
    }

    @Test
    fun `snap goes to nearest edge hiding all but a sliver`() {
        // 屏宽 1000、球 100、隐藏比例 0.4 → 左贴边 x=-40，右贴边 x=940；吸附即贴边隐藏态。
        val left = snapTo(x = 100f, y = 300, ballSizePx = 100, screenWidthPx = 1000, edgeHideRatio = 0.4)
        assertThat(left).isEqualTo(BallPosition(x = -40, y = 300, side = BallSide.LEFT, hidden = true))
        val right = snapTo(x = 800f, y = 300, ballSizePx = 100, screenWidthPx = 1000, edgeHideRatio = 0.4)
        assertThat(right).isEqualTo(BallPosition(x = 940, y = 300, side = BallSide.RIGHT, hidden = true))
    }

    @Test
    fun `snap clamps hide ratio`() {
        assertThat(hidePx(ballSizePx = 100, edgeHideRatio = 0.95)).isEqualTo(85)
        val snapped = snapTo(x = 50f, y = 0, ballSizePx = 100, screenWidthPx = 1000, edgeHideRatio = 0.95)
        assertThat(snapped.x).isEqualTo(-85)
    }

    @Test
    fun `reveal brings hidden ball back inside keeping its side`() {
        val left = BallPosition(x = -80, y = 300, side = BallSide.LEFT, hidden = true)
        assertThat(revealPosition(left, screenWidthPx = 1000, ballSizePx = 100))
            .isEqualTo(BallPosition(x = 0, y = 300, side = BallSide.LEFT, hidden = false))
        val right = BallPosition(x = 980, y = 300, side = BallSide.RIGHT, hidden = true)
        assertThat(revealPosition(right, screenWidthPx = 1000, ballSizePx = 100))
            .isEqualTo(BallPosition(x = 900, y = 300, side = BallSide.RIGHT, hidden = false))
    }
}
