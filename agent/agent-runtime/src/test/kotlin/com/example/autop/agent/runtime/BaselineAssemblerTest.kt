/**
 * Baseline 装配（docs/tech/04 §3.6 第 4 步）：用户消息预算选取与截断、触发上下文渲染、
 * 最后观测包装（fullBlocks 优先、差量用 ToolResult 内容）、最近工具调用首行与覆盖区间。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.CompactionInput
import com.example.autop.agent.RenderItem
import com.example.autop.agent.runtime.compaction.BaselineAssembler
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.agent.runtime.testing.overridesOf
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.SystemPart
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BaselineAssemblerTest {
    private val assembler = BaselineAssembler(TestPromptRepository())

    @Test
    fun `userMessages 按预算从新到旧选取超出截断并恢复时间序`() {
        val data =
            input(
                userMessages =
                    listOf(
                        3L to Msg(Role.USER, listOf(Block.Text("AAAA"))),
                        2L to Msg(Role.USER, listOf(Block.Text("AAAA"))),
                        1L to Msg(Role.USER, listOf(Block.Text("A".repeat(16)))),
                    ),
                config = configWithKeep(12),
            )

        val msgs = assembler.userMessages(data)

        assertThat(msgs).hasSize(3)
        assertThat((msgs[0].blocks[0] as Block.Text).text).isEqualTo("A".repeat(8))
        assertThat((msgs[1].blocks[0] as Block.Text).text).isEqualTo("AAAA")
        assertThat((msgs[2].blocks[0] as Block.Text).text).isEqualTo("AAAA")
    }

    @Test
    fun `userMessages 预算为 0 时不选取`() {
        val data =
            input(
                userMessages = listOf(1L to Msg(Role.USER, listOf(Block.Text("AAAA")))),
                config = configWithKeep(0),
            )

        assertThat(assembler.userMessages(data)).isEmpty()
    }

    @Test
    fun `triggerText 渲染 trigger 前缀键`() {
        val text = assembler.triggerText(mapOf("trigger.source" to "通知", "trigger.time" to "9点", "other" to "x"))

        assertThat(text).isEqualTo("source: 通知\ntime: 9点")
        assertThat(assembler.triggerText(mapOf("other" to "x"))).isEmpty()
    }

    @Test
    fun `lastObservationMsg 优先用 fullBlocks 并保留图片`() {
        val item =
            RenderItem(
                seq = 5,
                msg = Msg(Role.TOOL, listOf(Block.ToolResult("c1", listOf(Block.Text("差量")), false))),
                observation = null,
                toolId = "tool.observe",
                fullBlocks = listOf(Block.Text("完整观测"), IMAGE),
            )

        val msg = assembler.lastObservationMsg(input(lastObservation = item))!!

        assertThat(msg.role).isEqualTo(Role.USER)
        assertThat(msg.blocks).hasSize(2)
        assertThat((msg.blocks[0] as Block.Text).text).contains("完整观测")
        assertThat(msg.blocks[1]).isEqualTo(IMAGE)
    }

    @Test
    fun `lastObservationMsg 无 fullBlocks 用 ToolResult 内容无观测返回 null`() {
        val diff =
            RenderItem(
                seq = 5,
                msg = Msg(Role.TOOL, listOf(Block.ToolResult("c1", listOf(Block.Text("差量观测")), false))),
                observation = null,
                toolId = "tool.observe",
            )

        val msg = assembler.lastObservationMsg(input(lastObservation = diff))

        assertThat((msg?.blocks?.get(0) as Block.Text).text).contains("差量观测")
        assertThat(assembler.lastObservationMsg(input(lastObservation = null))).isNull()
    }

    @Test
    fun `recentCallsText 取最近工具调用首行`() {
        val live =
            listOf(
                render(1, null, null),
                render(2, "tool.a", "第一行A\n第二行A"),
                render(3, "tool.b", "第一行B\n第二行B"),
            )

        assertThat(assembler.recentCallsText(live, max = 2)).isEqualTo("tool.a: 第一行A\ntool.b: 第一行B")
        assertThat(assembler.recentCallsText(live, max = 1)).isEqualTo("tool.b: 第一行B")
    }

    @Test
    fun `rangeOf 取首尾 seq 空为 0`() {
        assertThat(assembler.rangeOf(listOf(render(4, null, null), render(9, null, null)))).isEqualTo(4L to 9L)
        assertThat(assembler.rangeOf(emptyList())).isEqualTo(0L to 0L)
    }

    private fun render(
        seq: Long,
        toolId: String?,
        resultText: String?,
    ): RenderItem {
        val blocks =
            if (resultText == null) {
                listOf(Block.Text("消息$seq"))
            } else {
                listOf(Block.ToolResult("c$seq", listOf(Block.Text(resultText)), false))
            }
        return RenderItem(seq, Msg(Role.TOOL, blocks), null, toolId)
    }

    private fun input(
        userMessages: List<Pair<Long, Msg>> = emptyList(),
        lastObservation: RenderItem? = null,
        config: ConfigSnapshot = FakeConfigProvider().current(),
    ): CompactionInput =
        CompactionInput(
            threadId = ThreadId("t1"),
            system = listOf(SystemPart("系统提示")),
            live = emptyList(),
            userMessagesNewestFirst = userMessages,
            plan = emptyList(),
            runVars = emptyMap(),
            lastObservation = lastObservation,
            config = config,
        )

    private fun configWithKeep(keepUserMessageTokens: Int): ConfigSnapshot =
        FakeConfigProvider(
            overridesOf("agent", "compaction", "keepUserMessageTokens" to keepUserMessageTokens),
        ).current()

    private companion object {
        val IMAGE: Block.Image = Block.Image(fileRef = "blob:2:o.png", mime = "image/png", width = 4, height = 4)
    }
}
