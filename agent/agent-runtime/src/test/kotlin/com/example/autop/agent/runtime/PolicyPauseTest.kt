/**
 * 04 §6 项 4：WRITE_SCREEN 工具被策略拦截 → 错误结果 + RunPaused(POLICY_BLOCKED)，
 * 不执行 perform；resume（可带补充说明）后 Run 继续并正常结束。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunPaused
import com.example.autop.agent.RunResumed
import com.example.autop.agent.RunState
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.runtime.testing.FakePolicyEngine
import com.example.autop.agent.runtime.testing.FakeScreenTool
import com.example.autop.agent.runtime.testing.HarnessFakes
import com.example.autop.agent.runtime.testing.RecordingAuditSink
import com.example.autop.agent.runtime.testing.RuntimeHarness
import com.example.autop.llm.Block
import com.example.autop.policy.PolicyDecision
import com.example.autop.policy.PolicyErrorCodes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PolicyPauseTest {
    @Test
    fun `策略拦截写屏动作：错误结果加暂停 恢复后继续结束`() =
        runTest {
            val screen = FakeScreenTool()
            val policy =
                FakePolicyEngine {
                    PolicyDecision.Block(ruleId = "rule-9", category = "destructive", reasonKey = "reason.destructive")
                }
            val h = RuntimeHarness(tools = setOf(screen), fakes = HarnessFakes(policy = policy))
            h.llm
                .call("fake_screen" to "{}")
                .say("已停手")
            val thread = h.newThread()

            val handle = h.start(this, threadId = thread)
            handle.state.first { it == RunState.PAUSED }

            assertThat(screen.performed).isFalse()
            val blocked =
                h.store
                    .payloadsOf(thread)
                    .filterIsInstance<ToolResultEvent>()
                    .single()
            assertThat(blocked.ok).isFalse()
            assertThat(blocked.errorCode).isEqualTo(PolicyErrorCodes.POLICY_BLOCKED)

            handle.resume("继续吧")
            val finished = handle.await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            val payloads = h.store.payloadsOf(thread)
            assertThat(payloads.filterIsInstance<RunPaused>().single().reason).isEqualTo(PauseReason.POLICY_BLOCKED)
            assertThat(payloads.filterIsInstance<RunResumed>()).hasSize(1)
            val steer =
                payloads.filterIsInstance<UserMessage>().single { it.kind == UserMessageKind.STEER }
            assertThat((steer.blocks.first() as Block.Text).text).isEqualTo("继续吧")
        }

    @Test
    fun `策略放行时 perform 执行并写审计`() =
        runTest {
            val screen = FakeScreenTool()
            val policy = FakePolicyEngine { PolicyDecision.Allow }
            val audit = RecordingAuditSink()
            val h = RuntimeHarness(tools = setOf(screen), fakes = HarnessFakes(policy = policy, audit = audit))
            h.llm
                .call("fake_screen" to "{}")
                .say("完成")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            assertThat(screen.performed).isTrue()
            assertThat(audit.records).hasSize(2)
            assertThat(audit.records.last().ok).isTrue()
        }
}
