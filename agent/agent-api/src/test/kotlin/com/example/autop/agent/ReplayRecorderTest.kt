/**
 * 回放脚本录制（docs/tech/04 §3.12，R1/A6）：成功 Run 提取成功动作序列；
 * ask_user、策略拦截、steer、暂停、0 步或超步数都不适合录制。
 */
package com.example.autop.agent

import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.llm.Block
import com.example.autop.policy.PolicyErrorCodes
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class ReplayRecorderTest {
    @Test
    fun `成功 Run 录制成功动作并按 seq 排序`() {
        val events =
            listOf(
                event(2, toolResult(callId = "c1", ok = true, replay = step("tool.tap"))),
                event(1, toolResult(callId = "c0", ok = true, replay = step("tool.open_app"))),
                event(3, toolResult(callId = "c2", ok = false, replay = step("tool.swipe"))),
                event(4, RunFinished(status = RunOutcome.SUCCEEDED, summary = "完成")),
            )

        val script = ReplayRecorder.build(events, maxSteps = 10)

        assertThat(script?.sourceRunId).isEqualTo(RunId("run-1"))
        assertThat(script?.steps?.map { it.toolId }).containsExactly("tool.open_app", "tool.tap").inOrder()
    }

    @Test
    fun `ask_user、策略拦截、steer、暂停都不适合录制`() {
        val base =
            listOf(
                event(1, toolResult(callId = "c1", ok = true, replay = step("tool.tap"))),
                event(2, RunFinished(status = RunOutcome.SUCCEEDED, summary = "完成")),
            )
        val withAsk = base + event(3, toolResult(callId = "c2", toolId = "tool.ask_user", ok = true))
        val withBlocked =
            base + event(3, toolResult(callId = "c2", ok = false, errorCode = PolicyErrorCodes.POLICY_BLOCKED))
        val withSteer =
            base + event(3, UserMessage(blocks = listOf(Block.Text("改做别的")), kind = UserMessageKind.STEER))
        val withPause = base + event(3, RunPaused(reason = PauseReason.MANUAL, detail = "手动"))

        assertThat(ReplayRecorder.build(withAsk, maxSteps = 10)).isNull()
        assertThat(ReplayRecorder.build(withBlocked, maxSteps = 10)).isNull()
        assertThat(ReplayRecorder.build(withSteer, maxSteps = 10)).isNull()
        assertThat(ReplayRecorder.build(withPause, maxSteps = 10)).isNull()
    }

    @Test
    fun `无成功步骤或超过 maxSteps 返回 null`() {
        val onlyFailed =
            listOf(
                event(1, toolResult(callId = "c1", ok = false, replay = step("tool.tap"))),
                event(2, RunFinished(status = RunOutcome.SUCCEEDED, summary = "完成")),
            )
        val twoSteps =
            listOf(
                event(1, toolResult(callId = "c1", ok = true, replay = step("tool.tap"))),
                event(2, toolResult(callId = "c2", ok = true, replay = step("tool.swipe"))),
                event(3, RunFinished(status = RunOutcome.SUCCEEDED, summary = "完成")),
            )

        assertThat(ReplayRecorder.build(onlyFailed, maxSteps = 10)).isNull()
        assertThat(ReplayRecorder.build(twoSteps, maxSteps = 1)).isNull()
        val script = ReplayRecorder.build(twoSteps, maxSteps = 2)
        assertThat(script?.steps).hasSize(2)
    }

    @Test
    fun `没有 SUCCEEDED 不录制`() {
        val events =
            listOf(
                event(1, toolResult(callId = "c1", ok = true, replay = step("tool.tap"))),
                event(2, RunFinished(status = RunOutcome.FAILED, summary = "失败")),
            )

        assertThat(ReplayRecorder.build(events, maxSteps = 10)).isNull()
    }

    private fun event(
        seq: Long,
        payload: EventPayload,
    ): ThreadEvent = ThreadEvent(ThreadId("t1"), seq, RunId("run-1"), 1000L + seq, payload)

    private fun toolResult(
        callId: String,
        ok: Boolean,
        replay: ReplayableAction? = null,
        toolId: String = "tool.tap",
        errorCode: String? = null,
    ): ToolResultEvent =
        ToolResultEvent(
            callId = callId,
            toolId = toolId,
            ok = ok,
            blocks = listOf(Block.Text("结果")),
            errorCode = errorCode,
            durationMs = 1,
            replay = replay,
        )

    private fun step(toolId: String): ReplayableAction =
        ReplayableAction(
            toolId = toolId,
            replayArgs = JsonObject(emptyMap()),
            pre =
                ReplayStateSample(
                    packageName = "com.example",
                    activity = "Main",
                    fingerprint = "fp1",
                    keys = emptyList(),
                ),
            postFingerprint = "fp2",
            execution = "node_action",
        )
}
