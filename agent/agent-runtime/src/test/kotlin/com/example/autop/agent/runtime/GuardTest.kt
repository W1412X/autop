/**
 * 04 §6 项 9/10/11：预算触顶进入 finalRound 以 BUDGET_EXHAUSTED 收口；
 * 熔断 Warn 注入提示继续、Stop 注入提示并进入 finalRound；计划门拦下一次自然结束。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.PlanStatus
import com.example.autop.agent.PlanUpdated
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.runtime.testing.FakeTool
import com.example.autop.agent.runtime.testing.RuntimeHarness
import com.example.autop.agent.runtime.testing.overridesOf
import com.example.autop.agent.runtime.tools.control.UpdatePlanTool
import com.example.autop.core.common.AppError
import com.example.autop.core.common.ThreadId
import com.example.autop.llm.Block
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GuardTest {
    @Test
    fun `9 预算触顶：finalRound 只能总结 以 BUDGET_EXHAUSTED 收口`() =
        runTest {
            val tool = FakeTool()
            val h =
                RuntimeHarness(
                    tools = setOf(tool),
                    configOverrides = overridesOf("agent", "budget", "maxSteps" to 1),
                )
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .say("总计完成 3 项")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.BUDGET_EXHAUSTED)
            assertThat(finished.summary).isEqualTo("总计完成 3 项")
            // finalRound 请求不再带工具
            assertThat(h.llm.requests[1].tools).isEmpty()
            assertThat(tool.invocations).hasSize(1)
        }

    @Test
    fun `10a 熔断 Warn：注入提示后继续 并可正常结束`() =
        runTest {
            val tool = failingTool()
            val h =
                RuntimeHarness(
                    tools = setOf(tool),
                    configOverrides = overridesOf("agent", "breaker", "sameErrorWarn" to 2),
                )
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .call(FakeTool.FAKE_NAME to "{}")
                .say("换条路走")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            val injected = injectedTexts(h, thread)
            assertThat(injected).isNotEmpty()
            assertThat(h.llm.requests).hasSize(3)
        }

    @Test
    fun `10b 熔断 Stop：注入提示并以 BREAKER_STOPPED 收口（区别于预算耗尽）`() =
        runTest {
            val tool = failingTool()
            val h =
                RuntimeHarness(
                    tools = setOf(tool),
                    configOverrides =
                        overridesOf("agent", "breaker", "sameErrorStop" to 2, "sameErrorWarn" to 99),
                )
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .call(FakeTool.FAKE_NAME to "{}")
                .say("无法继续")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.BREAKER_STOPPED)
            assertThat(finished.errorCode).isEqualTo(AgentErrorCodes.BREAKER_STOPPED)
            assertThat(finished.summary).isEqualTo("无法继续")
            assertThat(h.llm.requests).hasSize(3)
            assertThat(injectedTexts(h, thread)).isNotEmpty()
        }

    @Test
    fun `11 计划门：有未完成项时拦下一次自然结束`() =
        runTest {
            val h = RuntimeHarness(tools = setOf(UpdatePlanTool()))
            h.llm
                .call("update_plan" to """{"items":[{"step":"核对账单","status":"pending"}]}""")
                .say("完成了")
                .call("update_plan" to """{"items":[{"step":"核对账单","status":"done"}]}""")
                .say("全部完成")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            assertThat(finished.summary).isEqualTo("全部完成")
            // 第一次自然结束被拦下（注入未完成项提示），共 4 轮采样
            assertThat(h.llm.requests).hasSize(4)
            assertThat(injectedTexts(h, thread)).isNotEmpty()
            val plans = h.store.payloadsOf(thread).filterIsInstance<PlanUpdated>()
            assertThat(
                plans
                    .last()
                    .items
                    .single()
                    .status,
            ).isEqualTo(PlanStatus.DONE)
        }

    @Test
    fun `11b 计划门关闭时不拦`() =
        runTest {
            val h =
                RuntimeHarness(
                    tools = setOf(UpdatePlanTool()),
                    configOverrides = overridesOf("agent", "plan", "blockFinishOnce" to false),
                )
            h.llm
                .call("update_plan" to """{"items":[{"step":"a","status":"pending"}]}""")
                .say("先到这")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            assertThat(finished.summary).isEqualTo("先到这")
            assertThat(h.llm.requests).hasSize(2)
        }

    private fun failingTool(): FakeTool =
        FakeTool { _, _ ->
            ToolOutput(
                ok = false,
                content = listOf(Block.Text("err")),
                error = AppError(code = "tool.err", message = "e"),
            )
        }

    private fun injectedTexts(
        h: RuntimeHarness,
        thread: ThreadId,
    ): List<String> =
        h.store
            .payloadsOf(thread)
            .filterIsInstance<UserMessage>()
            .filter { it.kind == UserMessageKind.INJECTED }
            .map { (it.blocks.single() as Block.Text).text }
}
