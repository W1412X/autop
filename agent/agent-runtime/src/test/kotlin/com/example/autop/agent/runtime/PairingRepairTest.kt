/**
 * 配对修复回归（N192）：孤儿 ToolCall（run 被取消/中断时 ToolResult 未落盘）补占位结果、
 * 缺失应答补齐、无主结果剔除——任何事件序列渲染出的消息序列都能通过 MsgValidator。
 * 此前 cancel 留下的孤儿调用会让同线程下一个 Run 的请求触发
 * "user message before previous tool calls were answered" run_crashed。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.EventPayload
import com.example.autop.agent.HistoryRenderRule
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.runtime.testing.InMemoryThreadStore
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.MsgValidator
import com.example.autop.llm.Role
import com.example.autop.llm.StopReason
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PairingRepairTest {
    @Test
    fun `孤儿 ToolCall 后的用户消息前补占位结果`() =
        runTest {
            val store = InMemoryThreadStore()
            val thread = store.createThread(null, null)
            store.append(thread, null, user("hi"))
            store.append(thread, null, assistantCalls("c1"))
            store.append(thread, null, user("新输入"))

            val msgs = render(store, thread)

            MsgValidator.validate(msgs)
            val toolIdx = msgs.indexOfFirst { it.role == Role.TOOL }
            assertThat(toolIdx).isEqualTo(2)
            val result = msgs[toolIdx].blocks.single() as Block.ToolResult
            assertThat(result.callId).isEqualTo("c1")
            assertThat(result.isError).isTrue()
            assertThat((result.content.single() as Block.Text).text).contains("未返回结果")
            assertThat(msgs[3].role).isEqualTo(Role.USER)
        }

    @Test
    fun `部分应答补齐缺失占位`() =
        runTest {
            val store = InMemoryThreadStore()
            val thread = store.createThread(null, null)
            store.append(thread, null, assistantCalls("c1", "c2"))
            store.append(thread, null, toolResult("c1", "真结果"))
            store.append(thread, null, assistantText("继续"))

            val msgs = render(store, thread)

            MsgValidator.validate(msgs)
            val blocks = msgs[1].blocks.filterIsInstance<Block.ToolResult>()
            assertThat(blocks.map { it.callId }).containsExactly("c1", "c2")
            assertThat((blocks[0].content.single() as Block.Text).text).isEqualTo("真结果")
            assertThat(blocks[1].isError).isTrue()
        }

    @Test
    fun `结尾孤儿 ToolCall 补占位`() =
        runTest {
            val store = InMemoryThreadStore()
            val thread = store.createThread(null, null)
            store.append(thread, null, user("hi"))
            store.append(thread, null, assistantCalls("c1"))

            val msgs = render(store, thread)

            MsgValidator.validate(msgs)
            assertThat(msgs.last().role).isEqualTo(Role.TOOL)
            assertThat((msgs.last().blocks.single() as Block.ToolResult).callId).isEqualTo("c1")
        }

    @Test
    fun `无主 ToolResult 丢弃`() =
        runTest {
            val store = InMemoryThreadStore()
            val thread = store.createThread(null, null)
            store.append(thread, null, user("hi"))
            store.append(thread, null, assistantText("好"))
            store.append(thread, null, toolResult("ghost", "孤儿结果"))
            store.append(thread, null, user("再问"))

            val msgs = render(store, thread)

            MsgValidator.validate(msgs)
            assertThat(msgs.none { it.role == Role.TOOL }).isTrue()
        }

    @Test
    fun `正常配对序列原样保留`() =
        runTest {
            val store = InMemoryThreadStore()
            val thread = store.createThread(null, null)
            store.append(thread, null, user("hi"))
            store.append(thread, null, assistantCalls("c1"))
            store.append(thread, null, toolResult("c1", "真结果"))
            store.append(thread, null, assistantText("完成"))

            val msgs = render(store, thread)

            MsgValidator.validate(msgs)
            assertThat(msgs.map { it.role })
                .containsExactly(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT)
                .inOrder()
            assertThat((msgs[2].blocks.single() as Block.ToolResult).content.single())
                .isEqualTo(Block.Text("真结果"))
        }

    private suspend fun render(
        store: InMemoryThreadStore,
        thread: com.example.autop.core.common.ThreadId,
    ): List<Msg> =
        HistoryRenderer(store, emptySet<HistoryRenderRule>())
            .messages(thread, FakeConfigProvider().current())

    private fun user(text: String): EventPayload =
        UserMessage(blocks = listOf(Block.Text(text)), kind = UserMessageKind.INPUT)

    private fun assistantCalls(vararg ids: String): EventPayload =
        AssistantMessage(
            blocks = ids.map { Block.ToolCall(it, "fake", "{}") },
            usage = null,
            stopReason = StopReason.TOOL_USE,
            modelProfileId = "m",
        )

    private fun assistantText(text: String): EventPayload =
        AssistantMessage(
            blocks = listOf(Block.Text(text)),
            usage = null,
            stopReason = StopReason.END_TURN,
            modelProfileId = "m",
        )

    private fun toolResult(
        callId: String,
        text: String,
    ): EventPayload =
        ToolResultEvent(
            callId = callId,
            toolId = "tool.fake",
            ok = true,
            blocks = listOf(Block.Text(text)),
            durationMs = 1L,
        )
}
