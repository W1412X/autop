/**
 * 04 §6 第 19 项：全部匹配→注入 `agent.replay.completed` 后继续主循环；
 * 第 k 步相似度不足→停在 k、注入 `agent.replay.diverged`（含剩余步骤）后继续；
 * 未绑定 probe 时跳过回放前缀。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.MessageOrigin
import com.example.autop.agent.ReplayConfig
import com.example.autop.agent.ReplayPlan
import com.example.autop.agent.ReplayScript
import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayStateSample
import com.example.autop.agent.ReplayableAction
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.runtime.testing.FakePolicyEngine
import com.example.autop.agent.runtime.testing.FakeScreenTool
import com.example.autop.agent.runtime.testing.HarnessFakes
import com.example.autop.agent.runtime.testing.RuntimeHarness
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.policy.PolicyDecision
import com.example.autop.policy.PolicyErrorCodes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class ReplayTurnLoopTest {
    @Test
    fun `全部匹配则注入 completed 并继续主循环`() =
        runTest {
            val tool = FakeScreenTool()
            val sample = sampleOf(keys = listOf("k"))
            val h =
                RuntimeHarness(
                    tools = setOf(tool),
                    fakes = HarnessFakes(replayProbe = fixedProbe(sample)),
                )
            val plan = planOf(listOf(step(pre = sample), step(pre = sample)))
            h.llm.say("做完了")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread, replay = plan).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            assertThat(finished.summary).isEqualTo("做完了")
            assertThat(tool.performed).isTrue()
            val payloads = h.store.payloadsOf(thread)
            assertThat(payloads.filterIsInstance<AssistantMessage>().map { it.origin })
                .containsExactly(MessageOrigin.REPLAY, MessageOrigin.REPLAY, MessageOrigin.MODEL)
                .inOrder()
            val injected = payloads.filterIsInstance<UserMessage>().filter { it.kind == UserMessageKind.INJECTED }
            assertThat(injected).hasSize(1)
            assertThat(
                injected
                    .single()
                    .blocks
                    .first()
                    .toString(),
            ).contains("2")
        }

    @Test
    fun `第 k 步相似度不足则停在该步 注入 diverged 并继续`() =
        runTest {
            val tool = FakeScreenTool()
            val wrong = sampleOf(keys = listOf("zzz"))
            val h =
                RuntimeHarness(
                    tools = setOf(tool),
                    fakes = HarnessFakes(replayProbe = fixedProbe(wrong)),
                )
            val plan =
                planOf(listOf(step(pre = sampleOf(keys = listOf("k"))), step(pre = sampleOf(keys = listOf("k")))))
            h.llm.say("接着做")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread, replay = plan).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            assertThat(tool.performed).isFalse()
            val payloads = h.store.payloadsOf(thread)
            assertThat(payloads.filterIsInstance<AssistantMessage>().map { it.origin })
                .containsExactly(MessageOrigin.MODEL)
                .inOrder()
            val injected = payloads.filterIsInstance<UserMessage>().filter { it.kind == UserMessageKind.INJECTED }
            assertThat(injected).hasSize(1)
            val text = injected.single().blocks.joinToString(" ") { it.toString() }
            assertThat(text).contains("tool.fake_screen")
        }

    @Test
    fun `回放动作同样被策略拦截`() =
        runTest {
            val tool = FakeScreenTool()
            val sample = sampleOf(keys = listOf("k"))
            val h =
                RuntimeHarness(
                    tools = setOf(tool),
                    fakes =
                        HarnessFakes(
                            policy = FakePolicyEngine { PolicyDecision.Block("rule-9", "destructive", "reason.x") },
                            replayProbe = fixedProbe(sample),
                        ),
                )
            val plan = planOf(listOf(step(pre = sample)))
            h.llm.say("收到")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread, replay = plan).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            assertThat(tool.performed).isFalse()
            val payloads = h.store.payloadsOf(thread)
            val blocked = payloads.filterIsInstance<ToolResultEvent>().single()
            assertThat(blocked.ok).isFalse()
            assertThat(blocked.errorCode).isEqualTo(PolicyErrorCodes.POLICY_BLOCKED)
            val injected = payloads.filterIsInstance<UserMessage>().filter { it.kind == UserMessageKind.INJECTED }
            assertThat(injected).hasSize(1)
        }

    @Test
    fun `未绑定 probe 时跳过回放前缀`() =
        runTest {
            val tool = FakeScreenTool()
            val h = RuntimeHarness(tools = setOf(tool))
            val plan = planOf(listOf(step(pre = sampleOf(keys = listOf("k")))))
            h.llm.say("好")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread, replay = plan).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            assertThat(tool.performed).isFalse()
            val payloads = h.store.payloadsOf(thread)
            assertThat(payloads.filterIsInstance<AssistantMessage>().map { it.origin })
                .containsExactly(MessageOrigin.MODEL)
                .inOrder()
            assertThat(payloads.filterIsInstance<UserMessage>().filter { it.kind == UserMessageKind.INJECTED })
                .isEmpty()
        }

    private fun planOf(steps: List<ReplayableAction>): ReplayPlan =
        ReplayPlan(
            script = ReplayScript(sourceRunId = RunId("run-0"), steps = steps),
            config =
                ReplayConfig(
                    preStateWaitMs = 0,
                    pollMs = 0,
                    threshold = 0.85,
                    keysWeight = 0.85,
                    appWeight = 0.15,
                ),
        )

    private fun step(pre: ReplayStateSample): ReplayableAction =
        ReplayableAction(
            toolId = "tool.fake_screen",
            replayArgs = JsonObject(mapOf("_locator" to JsonPrimitive("#n1"))),
            pre = pre,
            postFingerprint = null,
            execution = "node_action",
        )

    private fun sampleOf(keys: List<String>): ReplayStateSample =
        ReplayStateSample(
            packageName = "com.example",
            activity = "Main",
            fingerprint = "fp",
            keys = keys,
            observeMode = "a11y",
        )

    private fun fixedProbe(sample: ReplayStateSample): ReplayStateProbe =
        object : ReplayStateProbe {
            override suspend fun sample(
                observeMode: String,
                config: ConfigSnapshot,
            ): Outcome<ReplayStateSample> = Outcome.Ok(sample)
        }
}
