/**
 * 发送前历史净化（agent-harness-overhaul WP6.11）：每条规则一例；
 * 配对在净化后保持不变。
 */
package com.example.autop.agent.protocol

import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HistorySanitizerTest {
    private val texts = SanitizeTexts(emptyToolResult = "[空结果]")

    private fun target(
        keepThinking: Boolean = false,
        requireSignature: Boolean = true,
    ) = SanitizeTarget(
        profileId = "p1",
        keepThinking = keepThinking,
        requireThinkingSignature = requireSignature,
    )

    @Test
    fun `blank text is dropped`() {
        val out = HistorySanitizer.sanitize(listOf(textMsg("   ")), target(), texts)
        assertThat(out).isEmpty()
    }

    @Test
    fun `thinking from other profile is dropped`() {
        val msg =
            Msg(Role.ASSISTANT, listOf(Block.Thinking("推理")), producedBy = "p2")
        val out = HistorySanitizer.sanitize(listOf(msg), target(keepThinking = true), texts)
        assertThat(out).isEmpty()
    }

    @Test
    fun `unsigned thinking is dropped for anthropic signature requirement`() {
        val msg = Msg(Role.ASSISTANT, listOf(Block.Thinking("推理")), producedBy = "p1")
        val out = HistorySanitizer.sanitize(listOf(msg), target(keepThinking = true), texts)
        assertThat(out).isEmpty()
    }

    @Test
    fun `signed thinking is kept when keepThinking`() {
        val msg =
            Msg(
                Role.ASSISTANT,
                listOf(Block.Thinking("推理", signature = "sig")),
                producedBy = "p1",
            )
        val out = HistorySanitizer.sanitize(listOf(msg), target(keepThinking = true), texts)
        assertThat(out).hasSize(1)
    }

    @Test
    fun `unsigned thinking is kept when echo mode`() {
        val msg = Msg(Role.ASSISTANT, listOf(Block.Thinking("推理")), producedBy = "p1")
        val out =
            HistorySanitizer.sanitize(
                listOf(msg),
                target(keepThinking = true, requireSignature = false),
                texts,
            )
        assertThat(out).hasSize(1)
    }

    @Test
    fun `image is kept because every model accepts images`() {
        val msg = Msg(Role.USER, listOf(Block.Image("ref", "image/png", 1, 1)))
        val out = HistorySanitizer.sanitize(listOf(msg), target(), texts)
        assertThat(out.single().blocks.single()).isInstanceOf(Block.Image::class.java)
    }

    @Test
    fun `invalid tool call args are rewritten keeping the original`() {
        val call = Block.ToolCall(id = "c1", name = "tap", argsJson = "not json")
        val out = HistorySanitizer.sanitize(listOf(Msg(Role.ASSISTANT, listOf(call))), target(), texts)
        val fixed = out.single().blocks.single() as Block.ToolCall
        assertThat(fixed.argsJson).contains("_invalid_arguments")
        assertThat(fixed.argsJson).contains("not json")
    }

    @Test
    fun `empty tool result gets placeholder text`() {
        val result = Block.ToolResult(callId = "c1", content = listOf(Block.Text("  ")), isError = false)
        val out = HistorySanitizer.sanitize(listOf(Msg(Role.TOOL, listOf(result))), target(), texts)
        val fixed = out.single().blocks.single() as Block.ToolResult
        assertThat((fixed.content.single() as Block.Text).text).isEqualTo("[空结果]")
    }

    @Test
    fun `pairing is preserved after sanitize`() {
        val messages =
            listOf(
                Msg(Role.ASSISTANT, listOf(Block.ToolCall("c1", "tap", "{}"))),
                Msg(Role.TOOL, listOf(Block.ToolResult("c1", listOf(Block.Text("ok")), false))),
            )
        val out = HistorySanitizer.sanitize(messages, target(), texts)
        val calls = out.flatMap { it.blocks }.filterIsInstance<Block.ToolCall>().map { it.id }
        val results = out.flatMap { it.blocks }.filterIsInstance<Block.ToolResult>().map { it.callId }
        assertThat(calls).containsExactly("c1")
        assertThat(results).containsExactly("c1")
    }

    private fun textMsg(text: String): Msg = Msg(Role.USER, listOf(Block.Text(text)))
}
