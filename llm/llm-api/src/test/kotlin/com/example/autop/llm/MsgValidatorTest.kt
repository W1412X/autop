package com.example.autop.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MsgValidatorTest {
    @Test
    fun `plain text conversation is valid`() {
        MsgValidator.validate(
            listOf(
                Msg(Role.USER, listOf(Block.Text("hi"))),
                Msg(Role.ASSISTANT, listOf(Block.Text("hello"))),
                Msg(Role.USER, listOf(Block.Text("bye"))),
            ),
        )
    }

    @Test
    fun `tool call answered by tool result is valid`() {
        MsgValidator.validate(
            listOf(
                Msg(Role.USER, listOf(Block.Text("weather?"))),
                Msg(Role.ASSISTANT, listOf(Block.ToolCall("c1", "get_weather", "{}"))),
                Msg(Role.TOOL, listOf(Block.ToolResult("c1", listOf(Block.Text("sunny")), isError = false))),
                Msg(Role.ASSISTANT, listOf(Block.Text("sunny"))),
            ),
        )
    }

    @Test
    fun `tool call in user message is rejected`() {
        val e =
            assertThrows(IllegalStateException::class.java) {
                MsgValidator.validate(
                    listOf(
                        Msg(Role.USER, listOf(Block.ToolCall("c1", "f", "{}"))),
                        Msg(Role.TOOL, listOf(Block.ToolResult("c1", listOf(Block.Text("x")), isError = false))),
                    ),
                )
            }
        assertThat(e).hasMessageThat().contains("tool_call block outside assistant message")
    }

    @Test
    fun `tool result in assistant message is rejected`() {
        val e =
            assertThrows(IllegalStateException::class.java) {
                MsgValidator.validate(
                    listOf(
                        Msg(Role.ASSISTANT, listOf(Block.ToolResult("c1", listOf(Block.Text("x")), isError = false))),
                    ),
                )
            }
        assertThat(e).hasMessageThat().contains("tool_result block outside tool message")
    }

    @Test
    fun `tool message with text block is rejected`() {
        val e =
            assertThrows(IllegalStateException::class.java) {
                MsgValidator.validate(
                    listOf(
                        Msg(Role.ASSISTANT, listOf(Block.ToolCall("c1", "f", "{}"))),
                        Msg(Role.TOOL, listOf(Block.Text("raw"), Block.ToolResult("c1", emptyList(), false))),
                    ),
                )
            }
        assertThat(e).hasMessageThat().contains("non-tool_result")
    }

    @Test
    fun `mismatched call ids are rejected`() {
        val e =
            assertThrows(IllegalStateException::class.java) {
                MsgValidator.validate(
                    listOf(
                        Msg(Role.ASSISTANT, listOf(Block.ToolCall("c1", "f", "{}"))),
                        Msg(Role.TOOL, listOf(Block.ToolResult("c2", emptyList(), false))),
                    ),
                )
            }
        assertThat(e).hasMessageThat().contains("do not match pending tool calls")
    }

    @Test
    fun `unanswered tool calls at end are rejected`() {
        val e =
            assertThrows(IllegalStateException::class.java) {
                MsgValidator.validate(
                    listOf(
                        Msg(Role.ASSISTANT, listOf(Block.ToolCall("c1", "f", "{}"))),
                    ),
                )
            }
        assertThat(e).hasMessageThat().contains("unanswered tool calls")
    }

    @Test
    fun `duplicate tool call id in one message is rejected`() {
        val e =
            assertThrows(IllegalStateException::class.java) {
                MsgValidator.validate(
                    listOf(
                        Msg(
                            Role.ASSISTANT,
                            listOf(Block.ToolCall("c1", "f", "{}"), Block.ToolCall("c1", "g", "{}")),
                        ),
                    ),
                )
            }
        assertThat(e).hasMessageThat().contains("duplicate tool call id")
    }

    @Test
    fun `user message before tool results is rejected`() {
        val e =
            assertThrows(IllegalStateException::class.java) {
                MsgValidator.validate(
                    listOf(
                        Msg(Role.ASSISTANT, listOf(Block.ToolCall("c1", "f", "{}"))),
                        Msg(Role.USER, listOf(Block.Text("interrupt"))),
                    ),
                )
            }
        assertThat(e).hasMessageThat().contains("user message before previous tool calls")
    }
}
