/**
 * 04 §6 基础回合测试：1. 无工具一轮纯文本自然结束；2. 工具调用后再采样一轮结束；
 * 采样失败以 FAILED 收口。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.runtime.testing.FakeTool
import com.example.autop.agent.runtime.testing.RuntimeHarness
import com.example.autop.core.common.AppError
import com.example.autop.core.common.ThreadId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TurnLoopBasicsTest {
    @Test
    fun `1 无工具一轮纯文本自然结束`() =
        runTest {
            val h = RuntimeHarness()
            h.llm.say("你好！")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            assertThat(finished.summary).isEqualTo("你好！")
            assertThat(eventKinds(h, thread))
                .containsExactly("RunStarted", "UserMessage", "AssistantMessage", "RunFinished")
                .inOrder()
        }

    @Test
    fun `2 工具调用后继续采样 第二轮自然结束`() =
        runTest {
            val tool = FakeTool()
            val h = RuntimeHarness(tools = setOf(tool))
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .say("做完了")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            assertThat(finished.summary).isEqualTo("做完了")
            assertThat(tool.invocations).hasSize(1)
            assertThat(eventKinds(h, thread))
                .containsExactly(
                    "RunStarted",
                    "UserMessage",
                    "AssistantMessage",
                    "ToolResultEvent",
                    "AssistantMessage",
                    "RunFinished",
                ).inOrder()
        }

    @Test
    fun `工具结果写入 callId 与 toolId`() =
        runTest {
            val tool = FakeTool(id = "tool.fake")
            val h = RuntimeHarness(tools = setOf(tool))
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread).await()

            val result =
                h.store
                    .payloadsOf(thread)
                    .filterIsInstance<ToolResultEvent>()
                    .single()
            assertThat(result.toolId).isEqualTo("tool.fake")
            assertThat(result.ok).isTrue()
            assertThat(result.errorCode).isNull()
        }

    @Test
    fun `采样失败 Run 以 FAILED 收口并带错误码`() =
        runTest {
            val h = RuntimeHarness()
            h.llm.fail(AppError(code = "llm.network", message = "boom"))
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.FAILED)
            assertThat(finished.errorCode).isEqualTo("llm.network")
            assertThat(eventKinds(h, thread)).contains("RunFinished")
        }

    @Test
    fun `流式输出转发 TextDelta 实时事件`() =
        runTest {
            val h = RuntimeHarness()
            h.llm.say("你好")
            val thread = h.newThread()

            val handle = h.start(this, threadId = thread)
            val finished = handle.await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
        }

    private fun eventKinds(
        h: RuntimeHarness,
        thread: ThreadId,
    ): List<String> = h.store.payloadsOf(thread).map { it::class.simpleName ?: "?" }
}
