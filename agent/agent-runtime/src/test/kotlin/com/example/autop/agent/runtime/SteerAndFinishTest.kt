/**
 * 04 §6 项 5/6 与 R1/B2（项 17）：运行中 steer 注入为 STEER 消息；steer 与 finish
 * 竞争时 finish 被作废（注入 agent.finish.deferred_by_steer）并继续循环。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunHandle
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.UserInteraction
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.runtime.testing.FakeTool
import com.example.autop.agent.runtime.testing.HarnessFakes
import com.example.autop.agent.runtime.testing.RuntimeHarness
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.agent.runtime.testing.okText
import com.example.autop.agent.runtime.tools.control.AskUserTool
import com.example.autop.agent.runtime.tools.control.FinishTool
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.llm.Block
import com.example.autop.llm.MsgValidator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SteerAndFinishTest {
    @Test
    fun `5 运行中 steer 注入为 STEER 用户消息并进入下一轮`() =
        runTest {
            lateinit var handle: RunHandle
            val tool =
                FakeTool { _, _ ->
                    handle.steer("改用方案 B")
                    okText("done")
                }
            val h = RuntimeHarness(tools = setOf(tool))
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .say("好的，改用方案 B")
            val thread = h.newThread()

            handle = h.start(this, threadId = thread)
            val finished = handle.await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            val steers =
                h.store
                    .payloadsOf(thread)
                    .filterIsInstance<UserMessage>()
                    .filter { it.kind == UserMessageKind.STEER }
            assertThat(steers).hasSize(1)
            assertThat((steers.single().blocks.single() as Block.Text).text).isEqualTo("改用方案 B")
            // 下一轮请求里 steer 已进入消息历史
            val second = h.llm.requests[1]
            assertThat(second.messages.any { msg -> msg.blocks.any { (it as? Block.Text)?.text == "改用方案 B" } })
                .isTrue()
        }

    @Test
    fun `17 steer 竞争时 finish 作废并继续循环`() =
        runTest {
            lateinit var handle: RunHandle
            val tool =
                FakeTool { _, _ ->
                    handle.steer("先别结束，再检查一遍")
                    okText("done")
                }
            val h = RuntimeHarness(tools = setOf(tool, FinishTool(TestPromptRepository())))
            h.llm
                .call(
                    FakeTool.FAKE_NAME to "{}",
                    "finish" to """{"status":"success","summary":"提前交付"}""",
                ).say("检查过了，现在完成")
            val thread = h.newThread()

            handle = h.start(this, threadId = thread)
            val finished = handle.await()

            // finish 被作废：最终 summary 来自第二轮，而不是「提前交付」
            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            assertThat(finished.summary).isEqualTo("检查过了，现在完成")
            val payloads = h.store.payloadsOf(thread)
            val finishes = payloads.filterIsInstance<RunFinished>()
            assertThat(finishes).hasSize(1)
            // 作废提示以 INJECTED 消息注入
            val injected =
                payloads
                    .filterIsInstance<UserMessage>()
                    .filter { it.kind == UserMessageKind.INJECTED }
                    .map { (it.blocks.single() as Block.Text).text }
            assertThat(injected.any { it.contains("结束") || it.contains("交付") || it.contains("steer") }).isTrue()
        }

    @Test
    fun `无 steer 时 finish 正常收口`() =
        runTest {
            val h = RuntimeHarness(tools = setOf(FinishTool(TestPromptRepository())))
            h.llm.call("finish" to """{"status":"success","summary":"交付结果"}""")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            assertThat(finished.summary).isEqualTo("交付结果")
            assertThat(h.llm.requests).hasSize(1)
        }

    @Test
    fun `18 resume 在 ask_user 等待中不插入未应答 ToolCall 之间`() =
        runTest {
            lateinit var handle: RunHandle
            val interaction =
                object : UserInteraction {
                    override suspend fun ask(
                        runId: RunId,
                        question: String,
                        options: List<String>,
                        timeoutMs: Long,
                    ): Outcome<String> {
                        // 回答前先恢复并附注（N192：此前 resume 的 note 直接落盘，会插进未应答
                        // 的 ToolCall 与 ToolResult 之间，下一轮请求 MsgValidator 直接崩）
                        handle.resume("补充说明")
                        return Outcome.Ok("好的")
                    }
                }
            val h =
                RuntimeHarness(
                    tools = setOf(AskUserTool(TestPromptRepository())),
                    fakes = HarnessFakes(interaction = interaction),
                )
            h.llm
                .call("ask_user" to """{"question":"继续吗"}""")
                .say("完成")
            val thread = h.newThread()

            handle = h.start(this, threadId = thread)
            val finished = handle.await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            // 每轮请求的消息序列都合法（此前在此触发 run_crashed）
            h.llm.requests.forEach { MsgValidator.validate(it.messages) }
            // note 落在工具结果之后（作为下一轮上下文）
            val second =
                h.llm.requests
                    .last()
                    .messages
            val toolIdx =
                second.indexOfFirst { msg ->
                    msg.blocks.any { it is Block.ToolResult }
                }
            val noteIdx =
                second.indexOfFirst { msg ->
                    msg.blocks.any { (it as? Block.Text)?.text == "补充说明" }
                }
            assertThat(toolIdx).isAtLeast(0)
            assertThat(noteIdx).isGreaterThan(toolIdx)
        }
}
