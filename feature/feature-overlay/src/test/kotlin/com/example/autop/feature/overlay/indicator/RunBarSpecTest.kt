/**
 * 运行控制条状态映射（BallVisualSpecTest 同款口径）：按钮组与标题按 Run 状态取值。
 */
package com.example.autop.feature.overlay.indicator

import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunState
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
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
    fun `运行中显示暂停与中止，标题回落当前操作`() {
        val spec = runBarSpecOf(active(status = RunStatus.RUNNING, currentStep = "点击「存储」", stepCount = 4))
        assertThat(spec.hint)
            .isEqualTo(RunBarHint.Running(title = "点击「存储」", stepCount = 4))
        assertThat(spec.buttons).containsExactly(RunBarAction.PAUSE, RunBarAction.STOP).inOrder()
    }

    @Test
    fun `运行中流程 Run 标题取流程提示`() {
        val spec =
            runBarSpecOf(
                active(status = RunStatus.RUNNING, currentStep = "点击")
                    .copy(flow = FlowRunProgress("清理缓存", FlowProgress(currentNodeName = "打开设置"))),
            )
        assertThat(spec.hint).isEqualTo(RunBarHint.Running(title = "流程：清理缓存 · 打开设置", stepCount = 1))
    }

    @Test
    fun `暂停显示继续与中止`() {
        val spec = runBarSpecOf(active(status = RunStatus.PAUSED, pauseReason = PauseReason.MANUAL))
        assertThat(spec.hint).isEqualTo(RunBarHint.Paused)
        assertThat(spec.buttons).containsExactly(RunBarAction.RESUME, RunBarAction.STOP).inOrder()
    }

    @Test
    fun `拦截显示继续与中止`() {
        val spec = runBarSpecOf(active(status = RunStatus.PAUSED, pauseReason = PauseReason.POLICY_BLOCKED))
        assertThat(spec.hint).isEqualTo(RunBarHint.Blocked)
        assertThat(spec.buttons).containsExactly(RunBarAction.RESUME, RunBarAction.STOP).inOrder()
    }

    @Test
    fun `等待回答只显示中止`() {
        val spec = runBarSpecOf(active(status = RunStatus.WAITING_USER, question = "先删哪个？"))
        assertThat(spec.hint).isEqualTo(RunBarHint.Waiting)
        assertThat(spec.buttons).containsExactly(RunBarAction.STOP)
    }

    @Test
    fun `排队中只显示中止`() {
        val spec = runBarSpecOf(active(status = RunStatus.QUEUED))
        assertThat(spec.hint).isEqualTo(RunBarHint.Queued)
        assertThat(spec.buttons).containsExactly(RunBarAction.STOP)
    }

    private fun active(
        status: RunStatus = RunStatus.RUNNING,
        currentStep: String? = null,
        stepCount: Int = 1,
        pauseReason: PauseReason? = null,
        question: String? = null,
    ): ActiveRunInfo =
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
                    pauseReason = pauseReason,
                    waitingQuestion = question,
                    waitingOptions = emptyList(),
                ),
            state = RunState.SAMPLING,
            currentStep = currentStep,
            stepCount = stepCount,
            plan = emptyList(),
        )
}
