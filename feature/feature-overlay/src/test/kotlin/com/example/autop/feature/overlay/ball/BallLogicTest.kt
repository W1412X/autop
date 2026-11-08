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
    fun `release near edge docks to nearest side showing a third`() {
        // 磁吸 40：球心 30（x=-20）≤ 40 → 左半贴边（露出 35 = 0.35×100，x = -65）；
        // 球心 980（x=930）≥ 960 → 右半贴边（x = 965）。
        val left = releasePosition(x = -20, y = 300, ballSizePx = 100, screenWidthPx = 1000, magnetPx = 40)
        assertThat(left).isEqualTo(BallPosition(x = -65, y = 300, side = BallSide.LEFT, dock = BallDock.EDGE))
        val right = releasePosition(x = 930, y = 300, ballSizePx = 100, screenWidthPx = 1000, magnetPx = 40)
        assertThat(right).isEqualTo(BallPosition(x = 965, y = 300, side = BallSide.RIGHT, dock = BallDock.EDGE))
    }

    @Test
    fun `release in middle stays free where dropped`() {
        val free = releasePosition(x = 400, y = 300, ballSizePx = 100, screenWidthPx = 1000, magnetPx = 40)
        assertThat(free).isEqualTo(BallPosition(x = 400, y = 300, side = BallSide.NONE, dock = BallDock.NONE))
    }

    @Test
    fun `sliver positions keep a tiny pill at the edge`() {
        assertThat(sliverX(BallSide.LEFT, ballSizePx = 100, screenWidthPx = 1000, sliverPx = 4))
            .isEqualTo(-96)
        assertThat(sliverX(BallSide.RIGHT, ballSizePx = 100, screenWidthPx = 1000, sliverPx = 4))
            .isEqualTo(996)
    }

    @Test
    fun `collapse to sliver keeps side and height`() {
        val docked = BallPosition(x = -65, y = 300, side = BallSide.LEFT, dock = BallDock.EDGE)
        val collapsed = collapseToSliver(docked, ballSizePx = 100, screenWidthPx = 1000, sliverPx = 4)
        assertThat(collapsed).isEqualTo(BallPosition(x = -96, y = 300, side = BallSide.LEFT, dock = BallDock.SLIVER))
        // 非半贴边不收。
        val free = BallPosition(x = 10, y = 10, side = BallSide.NONE, dock = BallDock.NONE)
        assertThat(collapseToSliver(free, 100, 1000, 4)).isEqualTo(free)
    }

    @Test
    fun `reveal brings docked ball back inside keeping its side`() {
        val left = BallPosition(x = -96, y = 300, side = BallSide.LEFT, dock = BallDock.SLIVER)
        assertThat(revealPosition(left, screenWidthPx = 1000, ballSizePx = 100))
            .isEqualTo(BallPosition(x = 0, y = 300, side = BallSide.LEFT, dock = BallDock.NONE))
        val right = BallPosition(x = 965, y = 300, side = BallSide.RIGHT, dock = BallDock.EDGE)
        assertThat(revealPosition(right, screenWidthPx = 1000, ballSizePx = 100))
            .isEqualTo(BallPosition(x = 900, y = 300, side = BallSide.RIGHT, dock = BallDock.NONE))
    }
}
