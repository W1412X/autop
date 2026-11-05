/**
 * 回放执行（docs/tech/04 §6 第 19 项）：全部匹配→completed；第 k 步相似度不足→停在 k、
 * diverged+剩余步骤；执行失败与未知工具同样在该步停下；回放合成消息
 * （origin=REPLAY）不带 `_` 前缀内部参数。
 */
package com.example.autop.agent.runtime.replay

import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.EventPayload
import com.example.autop.agent.MessageOrigin
import com.example.autop.agent.ReplayConfig
import com.example.autop.agent.ReplayPlan
import com.example.autop.agent.ReplayScript
import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayStateSample
import com.example.autop.agent.ReplayableAction
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolResultEvent
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class ReplayRunnerTest {
    @Test
    fun `全部步骤匹配则完整回放并收口`() =
        runTest {
            val steps = listOf(step("tool.a", keys = listOf("k")), step("tool.b", keys = listOf("k")))
            val emitted = mutableListOf<EventPayload>()
            val runner = runner(steps, probeOf { steps[it].pre }, emitted) { _, _ -> ok() }

            val outcome = runner.run()

            assertThat(outcome.completedSteps).isEqualTo(2)
            assertThat(outcome.totalSteps).isEqualTo(2)
            assertThat(outcome.diverged).isNull()
            assertThat(emitted.filterIsInstance<AssistantMessage>()).hasSize(2)
            assertThat(emitted.filterIsInstance<ToolResultEvent>()).hasSize(2)
            val assistant = emitted.filterIsInstance<AssistantMessage>().first()
            assertThat(assistant.origin).isEqualTo(MessageOrigin.REPLAY)
        }

    @Test
    fun `第 k 步状态不匹配则停在该步并给出剩余步骤`() =
        runTest {
            val steps =
                listOf(
                    step("tool.a", keys = listOf("k")),
                    step("tool.b", keys = listOf("other")),
                    step("tool.c", keys = listOf("k")),
                )
            val runner = runner(steps, probeOf { step("tool.x", keys = listOf("k")).pre }) { _, _ -> ok() }

            val outcome = runner.run()

            assertThat(outcome.completedSteps).isEqualTo(1)
            val divergence = outcome.diverged
            assertThat(divergence).isNotNull()
            assertThat(divergence?.stepIndex).isEqualTo(1)
            assertThat(divergence?.reason).isEqualTo(ReplayRunner.REASON_STATE_MISMATCH)
            val remaining = ReplayRunner.remainingText(planOf(steps), fromIndex = 1)
            assertThat(remaining.lines()).hasSize(2)
            assertThat(remaining).contains("tool.b")
            assertThat(remaining).contains("tool.c")
        }

    @Test
    fun `前置状态稍后出现则等待后通过`() =
        runTest {
            val target = step("tool.a", keys = listOf("k"))
            val wrong = step("tool.x", keys = listOf("z")).pre
            val probe = ScriptedProbe(listOf(wrong, target.pre))
            val runner = runner(listOf(target), probe, waitMs = 50, pollMs = 50) { _, _ -> ok() }

            val outcome = runner.run()

            assertThat(outcome.diverged).isNull()
            assertThat(outcome.completedSteps).isEqualTo(1)
        }

    @Test
    fun `语义版本不一致时跳过键比对不记分歧`() =
        runTest {
            // ADR-0007 决策 9：旧样本（v1）与新采样（v2）键不可比，只比包名/活动；
            // 步骤按定位器重新解析，键差异不记 divergence。
            val target = step("tool.a", keys = listOf("old-key"))
            val fresh = target.pre.copy(semanticsVersion = 2, keys = listOf("new-key"))
            val runner = runner(listOf(target), probeOf { fresh }) { _, _ -> ok() }

            val outcome = runner.run()

            assertThat(outcome.diverged).isNull()
            assertThat(outcome.completedSteps).isEqualTo(1)
        }

    @Test
    fun `执行失败在该步停下并带错误码`() =
        runTest {
            val steps = listOf(step("tool.a", keys = listOf("k")), step("tool.b", keys = listOf("k")))
            val runner =
                runner(steps, probeOf { steps[it].pre }) { index, _ ->
                    if (index == "rp_1") failed("policy.blocked") else ok()
                }

            val outcome = runner.run()

            assertThat(outcome.completedSteps).isEqualTo(1)
            assertThat(outcome.diverged?.stepIndex).isEqualTo(1)
            assertThat(outcome.diverged?.reason).isEqualTo("policy.blocked")
        }

    @Test
    fun `未知工具停在该步且不写事件`() =
        runTest {
            val steps = listOf(step("tool.gone", keys = listOf("k")))
            val emitted = mutableListOf<EventPayload>()
            val runner = runner(steps, probeOf { steps[it].pre }, emitted, names = emptyMap()) { _, _ -> ok() }

            val outcome = runner.run()

            assertThat(outcome.diverged?.reason).isEqualTo(ReplayRunner.REASON_UNKNOWN_TOOL)
            assertThat(emitted).isEmpty()
        }

    @Test
    fun `内部下划线参数不进历史`() =
        runTest {
            val action =
                step("tool.a", keys = listOf("k")).copy(
                    replayArgs = JsonObject(mapOf("_locator" to jsonStr("x"), "text" to jsonStr("hi"))),
                )
            val emitted = mutableListOf<EventPayload>()
            val runner = runner(listOf(action), probeOf { action.pre }, emitted) { _, _ -> ok() }

            runner.run()

            val assistant = emitted.filterIsInstance<AssistantMessage>().single()
            val call = assistant.blocks.single()
            assertThat(call).isInstanceOf(com.example.autop.llm.Block.ToolCall::class.java)
            val argsJson = (call as com.example.autop.llm.Block.ToolCall).argsJson
            assertThat(argsJson).contains("text")
            assertThat(argsJson).doesNotContain("_locator")
        }

    @Suppress("LongParameterList") // 测试装配面（可选参数带缺省值）的自然结果
    private fun runner(
        steps: List<ReplayableAction>,
        probe: ReplayStateProbe,
        emitted: MutableList<EventPayload> = mutableListOf(),
        names: Map<String, String> = mapOf("tool.a" to "a", "tool.b" to "b", "tool.c" to "c"),
        waitMs: Long = 0,
        pollMs: Long = 0,
        execute: suspend (String, ReplayableAction) -> ToolOutput,
    ): ReplayRunner =
        ReplayRunner(
            plan = planOf(steps, waitMs = waitMs, pollMs = pollMs),
            probe = probe,
            resolver = null,
            toolNameOf = { names[it] },
            emit = { emitted += it },
            executeStep = { callId, action -> ReplayStepResult(execute(callId, action), durationMs = 1) },
        )

    private fun planOf(
        steps: List<ReplayableAction>,
        waitMs: Long = 0,
        pollMs: Long = 0,
    ): ReplayPlan =
        ReplayPlan(
            script = ReplayScript(sourceRunId = RunId("run-1"), steps = steps),
            config =
                ReplayConfig(
                    preStateWaitMs = waitMs,
                    pollMs = pollMs,
                    threshold = 0.85,
                    keysWeight = 0.85,
                    appWeight = 0.15,
                ),
        )

    private fun probeOf(sampleAt: (Int) -> ReplayStateSample): ReplayStateProbe = ScriptedProbe(emptyList(), sampleAt)

    private fun step(
        toolId: String,
        keys: List<String>,
    ): ReplayableAction =
        ReplayableAction(
            toolId = toolId,
            replayArgs = JsonObject(emptyMap()),
            pre =
                ReplayStateSample(
                    packageName = "com.example",
                    activity = "Main",
                    fingerprint = "fp",
                    keys = keys,
                ),
            postFingerprint = null,
            execution = "node_action",
        )

    private fun ok(): ToolOutput = ToolOutput(ok = true, content = emptyList())

    private fun failed(code: String): ToolOutput =
        ToolOutput(ok = false, content = emptyList(), error = AppError(code = code, message = "x"))

    private fun jsonStr(value: String) = kotlinx.serialization.json.JsonPrimitive(value)

    /** 轮询采样替身：按下标取样本序列，越界重复最后一个；[sampleAt] 提供动态序列。 */
    private class ScriptedProbe(
        private val samples: List<ReplayStateSample>,
        private val sampleAt: ((Int) -> ReplayStateSample)? = null,
    ) : ReplayStateProbe {
        private var calls = 0

        override suspend fun sample(): Outcome<ReplayStateSample> {
            val index = calls
            calls += 1
            val s = sampleAt?.invoke(index) ?: samples[minOf(index, samples.size - 1)]
            return Outcome.Ok(s)
        }
    }
}
