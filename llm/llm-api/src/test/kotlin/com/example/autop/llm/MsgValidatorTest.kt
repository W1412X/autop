package com.example.autop.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MsgValidatorTest {
    @Test
    fun `plain text conversation is valid`() {
        val problems =
            MsgValidator.problems(
                listOf(
                    Msg(Role.USER, listOf(Block.Text("hi"))),
                    Msg(Role.ASSISTANT, listOf(Block.Text("hello"))),
                    Msg(Role.USER, listOf(Block.Text("bye"))),
                ),
            )
        assertThat(problems).isEmpty()
    }

    @Test
    fun `tool call answered by tool result is valid`() {
        val problems =
            MsgValidator.problems(
                listOf(
                    Msg(Role.USER, listOf(Block.Text("weather?"))),
                    Msg(Role.ASSISTANT, listOf(Block.ToolCall("c1", "get_weather", "{}"))),
                    Msg(Role.TOOL, listOf(Block.ToolResult("c1", listOf(Block.Text("sunny")), isError = false))),
                    Msg(Role.ASSISTANT, listOf(Block.Text("sunny"))),
                ),
            )
        assertThat(problems).isEmpty()
    }

    @Test
    fun `tool call in user message is reported`() {
        val problems =
            MsgValidator.problems(
                listOf(
                    Msg(Role.USER, listOf(Block.ToolCall("c1", "f", "{}"))),
                    Msg(Role.TOOL, listOf(Block.ToolResult("c1", listOf(Block.Text("x")), isError = false))),
                ),
            )
        assertThat(problems).contains("tool_call block outside assistant message")
    }

    @Test
    fun `tool result in assistant message is reported`() {
        val problems =
            MsgValidator.problems(
                listOf(
                    Msg(Role.ASSISTANT, listOf(Block.ToolResult("c1", listOf(Block.Text("x")), isError = false))),
                ),
            )
        assertThat(problems).contains("tool_result block outside tool message")
    }

    @Test
    fun `tool message with text block is reported`() {
        val problems =
            MsgValidator.problems(
                listOf(
                    Msg(Role.ASSISTANT, listOf(Block.ToolCall("c1", "f", "{}"))),
                    Msg(Role.TOOL, listOf(Block.Text("raw"), Block.ToolResult("c1", emptyList(), false))),
                ),
            )
        assertThat(problems).hasSize(1)
        assertThat(problems.single()).contains("non-tool_result block")
    }

    @Test
    fun `mismatched call ids are reported`() {
        val problems =
            MsgValidator.problems(
                listOf(
                    Msg(Role.ASSISTANT, listOf(Block.ToolCall("c1", "f", "{}"))),
                    Msg(Role.TOOL, listOf(Block.ToolResult("c2", emptyList(), false))),
                ),
            )
        assertThat(problems).contains("tool results [c2] do not match pending tool calls [c1]")
    }

    @Test
    fun `unanswered tool calls at end are reported`() {
        val problems =
            MsgValidator.problems(
                listOf(
                    Msg(Role.ASSISTANT, listOf(Block.ToolCall("c1", "f", "{}"))),
                ),
            )
        assertThat(problems).contains("message list ends with unanswered tool calls [c1]")
    }

    @Test
    fun `duplicate tool call id in one message is reported`() {
        val problems =
            MsgValidator.problems(
                listOf(
                    Msg(
                        Role.ASSISTANT,
                        listOf(Block.ToolCall("c1", "f", "{}"), Block.ToolCall("c1", "g", "{}")),
                    ),
                ),
            )
        assertThat(problems).contains("duplicate tool call id in one message: [c1, c1]")
    }

    @Test
    fun `user message before tool results is reported`() {
        val problems =
            MsgValidator.problems(
                listOf(
                    Msg(Role.ASSISTANT, listOf(Block.ToolCall("c1", "f", "{}"))),
                    Msg(Role.USER, listOf(Block.Text("interrupt"))),
                ),
            )
        assertThat(problems).contains("user message before previous tool calls were answered")
    }

    @Test
    fun `all violations are collected instead of stopping at the first`() {
        val problems =
            MsgValidator.problems(
                listOf(
                    Msg(Role.USER, listOf(Block.ToolCall("c1", "f", "{}"))),
                    Msg(Role.ASSISTANT, listOf(Block.ToolResult("c2", emptyList(), false))),
                ),
            )
        assertThat(problems).hasSize(2)
    }

    @Test
    fun `validate keeps throwing for assertion callers`() {
        val e =
            assertThrows(IllegalStateException::class.java) {
                MsgValidator.validate(listOf(Msg(Role.ASSISTANT, listOf(Block.ToolCall("c1", "f", "{}")))))
            }
        assertThat(e).hasMessageThat().contains("unanswered tool calls")
    }
}
