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
    private fun run(
        id: String,
        status: RunStatus = RunStatus.RUNNING,
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

    private fun active(
        record: RunRecord,
        stepCount: Int = 3,
    ): ActiveRunInfo = ActiveRunInfo(record, RunState.SAMPLING, null, stepCount, emptyList<PlanItem>())

    @Test
    fun `accessibility disconnected wins over running`() {
        val run = run("r1")
        val mode = deriveBallMode(active(run), listOf(run), accessibilityConnected = false, toolCategory = null)
        assertThat(mode).isEqualTo(BallMode.AccessibilityLost)
    }

    @Test
    fun `no open runs is idle and recording shows only when nothing is open`() {
        assertThat(deriveBallMode(null, emptyList(), accessibilityConnected = true, toolCategory = null))
            .isEqualTo(BallMode.Idle)
        assertThat(
            deriveBallMode(null, emptyList(), accessibilityConnected = true, toolCategory = null, recording = true),
        ).isEqualTo(BallMode.Recording)
        val run = run("r1")
        assertThat(
            deriveBallMode(
                active(run),
                listOf(run),
                accessibilityConnected = true,
                toolCategory = null,
                recording = true,
            ),
        ).isInstanceOf(BallMode.Running::class.java)
    }

    @Test
    fun `blocked beats waiting beats running beats paused across sessions`() {
        val blocked = run("b", RunStatus.PAUSED, pauseReason = PauseReason.POLICY_BLOCKED)
        val waiting = run("w", question = "继续吗？")
        val running = run("r")
        val paused = run("p", RunStatus.PAUSED, pauseReason = PauseReason.MANUAL)
        assertThat(deriveBallMode(active(running), listOf(running, blocked), true, null))
            .isEqualTo(BallMode.Blocked)
        assertThat(deriveBallMode(active(running), listOf(running, waiting), true, null))
            .isEqualTo(BallMode.WaitingAnswer)
        assertThat(deriveBallMode(active(paused), listOf(paused, running), true, null))
            .isInstanceOf(BallMode.Running::class.java)
        assertThat(deriveBallMode(null, listOf(paused), true, null)).isEqualTo(BallMode.Paused)
    }

    @Test
    fun `running focuses the active session when it is running`() {
        val run = run("r1")
        val mode =
            deriveBallMode(
                active(run, stepCount = 10),
                listOf(run),
                accessibilityConnected = true,
                toolCategory = ToolCategory.GESTURE,
            )
        assertThat(mode).isInstanceOf(BallMode.Running::class.java)
        val running = mode as BallMode.Running
        assertThat(running.stepCount).isEqualTo(10)
        assertThat(running.toolCategory).isEqualTo(ToolCategory.GESTURE)
        assertThat(running.state).isEqualTo(RunState.SAMPLING)
    }

    @Test
    fun `background running session is used when the active one is paused`() {
        val paused = run("p", RunStatus.PAUSED, pauseReason = PauseReason.MANUAL)
        val running = run("r")
        val mode = deriveBallMode(active(paused), listOf(paused, running), true, ToolCategory.TEXT)
        assertThat(mode).isInstanceOf(BallMode.Running::class.java)
        val focus = mode as BallMode.Running
        assertThat(focus.toolCategory).isNull()
        assertThat(focus.state).isEqualTo(RunState.SAMPLING)
    }

    @Test
    fun `running seed is stable for the same run and step`() {
        val run = run("r1")
        val a = deriveBallMode(active(run, 4), listOf(run), true, null) as BallMode.Running
        val b = deriveBallMode(active(run, 4), listOf(run), true, null) as BallMode.Running
        val c = deriveBallMode(active(run, 5), listOf(run), true, null) as BallMode.Running
        assertThat(a.seed).isEqualTo(b.seed)
        assertThat(c.seed).isNotEqualTo(a.seed)
    }

    @Test
    fun `tool categories map by semantics`() {
        assertThat(categoryOfTool("tool.tap")).isEqualTo(ToolCategory.GESTURE)
        assertThat(categoryOfTool("tool.tap_node")).isEqualTo(ToolCategory.GESTURE)
        assertThat(categoryOfTool("tool.scroll_node")).isEqualTo(ToolCategory.GESTURE)
        assertThat(categoryOfTool("tool.input_text")).isEqualTo(ToolCategory.TEXT)
        assertThat(categoryOfTool("tool.screenshot")).isEqualTo(ToolCategory.VISION)
        assertThat(categoryOfTool("tool.open_app")).isEqualTo(ToolCategory.APP)
        assertThat(categoryOfTool("tool.intent")).isEqualTo(ToolCategory.APP)
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
