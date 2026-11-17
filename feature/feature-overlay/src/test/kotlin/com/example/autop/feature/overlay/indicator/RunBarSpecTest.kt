/**
 * 运行控制条状态映射（BallVisualSpecTest 同款口径）：按钮组与标题按 Run 状态取值；
 * 标题走 hintTitleOf 变体（roll=0 取池首项，确定性断言）。
 */
package com.example.autop.feature.overlay.indicator

import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunState
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.flow.FlowProgress
import com.example.autop.flow.FlowRunProgress
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RunBarSpecTest {
    @Test
    fun `运行中显示暂停与中止，标题取工具变体`() {
        val spec =
            runBarSpecOf(
                active(RunStatus.RUNNING).copy(toolId = "tool.tap", toolTarget = "存储", stepCount = 4),
                roll = 0f,
            )
        assertThat(titleOf(spec.hint)).isEqualTo("正在点击「存储」")
        assertThat(spec.buttons).containsExactly(RunBarAction.PAUSE, RunBarAction.STOP).inOrder()
    }

    @Test
    fun `没有目标时也有工具文案`() {
        val spec = runBarSpecOf(active(RunStatus.RUNNING).copy(toolId = "tool.tap"), roll = 0f)
        assertThat(titleOf(spec.hint)).isEqualTo("点击中…")
    }

    @Test
    fun `思考中取状态短语`() {
        val spec = runBarSpecOf(active(RunStatus.RUNNING).copy(state = RunState.SAMPLING), roll = 0f)
        assertThat(titleOf(spec.hint)).isEqualTo("思考中…")
    }

    @Test
    fun `变体反重复：上一次说法降权`() {
        val active = active(RunStatus.RUNNING).copy(toolId = "tool.tap")
        val again = runBarSpecOf(active, previousHint = "点击中…", roll = 0f)
        val next = runBarSpecOf(active, previousHint = "点击中…", roll = 0.5f)
        assertThat(titleOf(again.hint)).isEqualTo("点击中…")
        assertThat(titleOf(next.hint)).isEqualTo("正在点击…")
    }

    @Test
    fun `运行中流程 Run 标题取流程提示`() {
        val spec =
            runBarSpecOf(
                active(RunStatus.RUNNING)
                    .copy(flow = FlowRunProgress("清理缓存", FlowProgress(currentNodeName = "打开设置"))),
                roll = 0f,
            )
        assertThat(titleOf(spec.hint)).isEqualTo("流程：清理缓存 · 打开设置")
    }

    @Test
    fun `暂停显示继续与中止`() {
        val spec = runBarSpecOf(active(RunStatus.PAUSED).withRun(pauseReason = PauseReason.MANUAL))
        assertThat(spec.hint).isEqualTo(RunBarHint.Paused)
        assertThat(spec.buttons).containsExactly(RunBarAction.RESUME, RunBarAction.STOP).inOrder()
    }

    @Test
    fun `拦截显示继续与中止`() {
        val spec = runBarSpecOf(active(RunStatus.PAUSED).withRun(pauseReason = PauseReason.POLICY_BLOCKED))
        assertThat(spec.hint).isEqualTo(RunBarHint.Blocked)
        assertThat(spec.buttons).containsExactly(RunBarAction.RESUME, RunBarAction.STOP).inOrder()
    }

    @Test
    fun `等待回答只显示中止`() {
        val spec = runBarSpecOf(active(RunStatus.WAITING_USER).withRun(question = "先删哪个？"))
        assertThat(spec.hint).isEqualTo(RunBarHint.Waiting)
        assertThat(spec.buttons).containsExactly(RunBarAction.STOP)
    }

    @Test
    fun `排队中只显示中止`() {
        val spec = runBarSpecOf(active(RunStatus.QUEUED))
        assertThat(spec.hint).isEqualTo(RunBarHint.Queued)
        assertThat(spec.buttons).containsExactly(RunBarAction.STOP)
    }

    @Test
    fun `控制条表情与控制条状态一一对应`() {
        val running = runBarSpecOf(active(RunStatus.RUNNING)).hint
        val paused = runBarSpecOf(active(RunStatus.PAUSED)).hint
        val blocked =
            runBarSpecOf(active(RunStatus.PAUSED).withRun(pauseReason = PauseReason.POLICY_BLOCKED)).hint
        val waiting = runBarSpecOf(active(RunStatus.WAITING_USER).withRun(question = "先删哪个？")).hint
        val queued = runBarSpecOf(active(RunStatus.QUEUED)).hint
        assertThat(runBarFaceOf(running)).isEqualTo(BallFaceMood.RUNNING)
        assertThat(runBarFaceOf(paused)).isEqualTo(BallFaceMood.PAUSED)
        assertThat(runBarFaceOf(blocked)).isEqualTo(BallFaceMood.BLOCKED)
        assertThat(runBarFaceOf(waiting)).isEqualTo(BallFaceMood.WAITING)
        assertThat(runBarFaceOf(queued)).isEqualTo(BallFaceMood.IDLE)
    }

    private fun titleOf(hint: RunBarHint): String? = (hint as? RunBarHint.Running)?.title

    /** RunRecord 层字段（暂停原因/等待问题）的改写入口。 */
    private fun ActiveRunInfo.withRun(
        pauseReason: PauseReason? = null,
        question: String? = null,
    ): ActiveRunInfo = copy(run = run.copy(pauseReason = pauseReason, waitingQuestion = question))

    private fun active(status: RunStatus): ActiveRunInfo =
        ActiveRunInfo(
            run =
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
                    pauseReason = null,
                    waitingQuestion = null,
                    waitingOptions = emptyList(),
                ),
            state = RunState.EXECUTING_TOOLS,
            currentStep = null,
            stepCount = 1,
            plan = emptyList(),
        )
}
