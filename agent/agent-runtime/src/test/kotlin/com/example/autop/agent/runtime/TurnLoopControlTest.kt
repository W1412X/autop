/**
 * 回合与批次控制语义（agent-harness-overhaul WP5.5）：
 * finish 的计划门只拦一次；MAX_TOKENS 配对截断调用并重试、超限失败；
 * 空回复重试后失败；拒答直接失败。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.runtime.testing.RuntimeHarness
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.agent.runtime.tools.control.FinishTool
import com.example.autop.agent.runtime.tools.control.UpdatePlanTool
import com.example.autop.llm.Block
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.StopReason
import com.example.autop.llm.Usage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TurnLoopControlTest {
    @Test
    fun `finish with open plan items is blocked once`() =
        runTest {
            val h = RuntimeHarness(tools = setOf(UpdatePlanTool(), FinishTool(TestPromptRepository())))
            h.llm
                .call("update_plan" to """{"items":[{"step":"核对账单","status":"pending"}]}""")
                .call("finish" to """{"status":"success","summary":"做完了"}""")
                .call("finish" to """{"status":"success","summary":"做完了"}""")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            // 第一次 finish 被计划门拦下（注入未完成项），第二次才收口：共 3 轮采样。
            assertThat(h.llm.requests).hasSize(3)
        }

    @Test
    fun `max tokens with tool calls pairs truncated results and retries`() =
        runTest {
            val h = RuntimeHarness()
            h.llm
                .enqueueTurn(truncatedWithCall())
                .say("好")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            assertThat(h.llm.requests).hasSize(2)
            val paired =
                h.store
                    .payloadsOf(thread)
                    .filterIsInstance<ToolResultEvent>()
                    .single { it.callId == "c-trunc" }
            assertThat(paired.ok).isFalse()
            assertThat(paired.errorCode).isEqualTo(AgentErrorCodes.OUTPUT_TRUNCATED)
        }

    @Test
    fun `max tokens beyond limit fails with output_truncated`() =
        runTest {
            val h = RuntimeHarness()
            h.llm
                .enqueueTurn(truncatedWithCall())
                .enqueueTurn(truncatedWithCall())
                .enqueueTurn(truncatedWithCall())
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.FAILED)
            assertThat(finished.errorCode).isEqualTo(AgentErrorCodes.OUTPUT_TRUNCATED)
        }

    @Test
    fun `empty reply retries then fails`() =
        runTest {
            val h = RuntimeHarness()
            h.llm.enqueueTurn(emptyReply()).enqueueTurn(emptyReply())
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.FAILED)
            assertThat(finished.errorCode).isEqualTo(AgentErrorCodes.EMPTY_REPLY)
            assertThat(h.llm.requests).hasSize(2)
        }

    @Test
    fun `refusal fails with model_refused`() =
        runTest {
            val h = RuntimeHarness()
            h.llm.enqueueTurn(refusal())
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.FAILED)
            assertThat(finished.errorCode).isEqualTo(AgentErrorCodes.MODEL_REFUSED)
        }

    private fun truncatedWithCall(): LlmStreamEvent.Completed =
        LlmStreamEvent.Completed(
            message = Msg(Role.ASSISTANT, listOf(Block.ToolCall(id = "c-trunc", name = "fake", argsJson = "{}"))),
            stopReason = StopReason.MAX_TOKENS,
            usage = Usage(inputTokens = 10, outputTokens = 10),
        )

    private fun emptyReply(): LlmStreamEvent.Completed =
        LlmStreamEvent.Completed(
            message = Msg(Role.ASSISTANT, listOf(Block.Text("   "))),
            stopReason = StopReason.END_TURN,
            usage = Usage(inputTokens = 10, outputTokens = 10),
        )

    private fun refusal(): LlmStreamEvent.Completed =
        LlmStreamEvent.Completed(
            message = Msg(Role.ASSISTANT, listOf(Block.Text("我不能做这个"))),
            stopReason = StopReason.REFUSAL,
            usage = Usage(inputTokens = 10, outputTokens = 10),
        )
}
