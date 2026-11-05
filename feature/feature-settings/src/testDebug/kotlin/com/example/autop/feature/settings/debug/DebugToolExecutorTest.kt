/**
 * [DebugToolExecutor] 单测：ScreenActionTool 的 prepare → 策略检查 → perform 流程，
 * Block 时返回 `policy.blocked` + Pause(POLICY_BLOCKED)（docs/tech/04 §3.3）。
 */
package com.example.autop.feature.settings.debug

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.PauseReason
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolControl
import com.example.autop.feature.settings.me.RecordingLogger
import com.example.autop.policy.PolicyDecision
import com.example.autop.policy.PolicyEngine
import com.example.autop.policy.PolicyErrorCodes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class DebugToolExecutorTest {
    private val prompts = FakePrompts()
    private val ctx = debugContext()

    @Test
    fun `blocked screen action returns policy blocked with pause`() =
        runTest {
            val decision =
                PolicyDecision.Block(ruleId = "rule-1", category = "payment", reasonKey = "policy_reason_payment")
            val executor = executorOf(FakeScreenActionTool(), policy = FakePolicyEngine(decision))
            val output = executor.execute("tool.tap", JsonObject(emptyMap()), ctx)
            assertThat(output.ok).isFalse()
            assertThat(output.error?.code).isEqualTo(PolicyErrorCodes.POLICY_BLOCKED)
            assertThat(output.error?.message).isEqualTo("rule-1")
            val control = output.control as ToolControl.Pause
            assertThat(control.reason).isEqualTo(PauseReason.POLICY_BLOCKED)
            assertThat(control.detail).isEqualTo("rule-1")
            assertThat(prompts.rendered["agent.policy.blocked_hint"]).containsKey("category")
        }

    @Test
    fun `allowed screen action performs the action`() =
        runTest {
            val tool = FakeScreenActionTool()
            val executor = executorOf(tool, policy = FakePolicyEngine(PolicyDecision.Allow))
            val output = executor.execute("tool.tap", JsonObject(emptyMap()), ctx)
            assertThat(output.ok).isTrue()
            assertThat(tool.performed).isTrue()
        }

    @Test
    fun `unknown tool returns unknown tool error`() =
        runTest {
            val output = executorOf(FakeTool()).execute("tool.none", JsonObject(emptyMap()), ctx)
            assertThat(output.error?.code).isEqualTo(AgentErrorCodes.UNKNOWN_TOOL)
        }

    @Test
    fun `crashing tool returns tool crashed`() =
        runTest {
            val tool = FakeTool(crash = true)
            val output = executorOf(tool).execute("tool.fake", JsonObject(emptyMap()), ctx)
            assertThat(output.error?.code).isEqualTo(AgentErrorCodes.TOOL_CRASHED)
        }

    private fun executorOf(
        tool: Tool,
        policy: PolicyEngine = FakePolicyEngine(PolicyDecision.Allow),
    ): DebugToolExecutor = DebugToolExecutor(setOf(tool), policy, prompts, RecordingLogger())
}
