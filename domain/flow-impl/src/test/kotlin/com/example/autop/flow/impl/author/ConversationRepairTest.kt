package com.example.autop.flow.impl.author

import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConversationRepairTest {
    private val cancelled = "CANCELLED"

    @Test
    fun `dangling tool calls get cancelled results so the next user message is valid`() {
        val history =
            mutableListOf(
                Msg(Role.USER, listOf(Block.Text("开始"))),
                Msg(
                    Role.ASSISTANT,
                    listOf(
                        Block.ToolCall("c1", "ask_user", "{}"),
                        Block.ToolCall("c2", "list_flows", "{}"),
                    ),
                ),
            )

        val repaired = history.repairDanglingToolCalls(cancelled)

        assertThat(repaired).isTrue()
        val tool = history.last()
        assertThat(tool.role).isEqualTo(Role.TOOL)
        val results = tool.blocks.filterIsInstance<Block.ToolResult>()
        assertThat(results.map { it.callId }).containsExactly("c1", "c2").inOrder()
        assertThat(results.all { it.isError }).isTrue()
    }

    @Test
    fun `history that already ends with tool results is left unchanged`() {
        val history =
            mutableListOf(
                Msg(Role.ASSISTANT, listOf(Block.ToolCall("c1", "ask_user", "{}"))),
                Msg(Role.TOOL, listOf(Block.ToolResult("c1", listOf(Block.Text("ok")), isError = false))),
            )

        assertThat(history.repairDanglingToolCalls(cancelled)).isFalse()
        assertThat(history).hasSize(2)
    }

    @Test
    fun `plain assistant text is not a dangling tool call`() {
        val history = mutableListOf(Msg(Role.ASSISTANT, listOf(Block.Text("你好"))))

        assertThat(history.repairDanglingToolCalls(cancelled)).isFalse()
        assertThat(history).hasSize(1)
    }
}
