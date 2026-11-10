/**
 * 请求组装（agent-harness-overhaul WP6.11）：他模型的思考块被丢弃、
 * 目标模型不支持图片时图片换占位、回退降级保留工具结果图片。
 */
package com.example.autop.agent.protocol

import com.example.autop.core.common.Outcome
import com.example.autop.core.config.AgentThinkingSection
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.core.prompt.PromptTemplate
import com.example.autop.core.prompt.RenderedPrompt
import com.example.autop.llm.Block
import com.example.autop.llm.ModelCapabilities
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RequestBuilderTest {
    private val texts = SanitizeTexts(imageUnsupported = "[图片占位]", emptyToolResult = "[空结果]")

    @Test
    fun `thinking from other profile is dropped`() {
        val builder = builder(profile(thinking = true))
        val msg =
            Msg(Role.ASSISTANT, listOf(Block.Thinking("推理", signature = "sig")), producedBy = "other")

        val request = builder.build(emptyList(), listOf(msg), emptyList(), finalRound = false)

        assertThat(request.messages).isEmpty()
    }

    @Test
    fun `images replaced when model lacks vision`() {
        val builder = builder(profile(vision = false))
        val msg = Msg(Role.USER, listOf(Block.Image("ref", "image/png", 1, 1), Block.Text("看")))

        val request = builder.build(emptyList(), listOf(msg), emptyList(), finalRound = false)

        val blocks = request.messages.single().blocks
        assertThat(blocks.filterIsInstance<Block.Image>()).isEmpty()
        assertThat((blocks[0] as Block.Text).text).isEqualTo("[图片占位]")
    }

    @Test
    fun `fallback downgrade keeps tool result images`() {
        val builder = builder(profile(tools = false, vision = true))
        val messages =
            listOf(
                Msg(Role.ASSISTANT, listOf(Block.ToolCall("c1", "screenshot", "{}"))),
                Msg(
                    Role.TOOL,
                    listOf(
                        Block.ToolResult(
                            callId = "c1",
                            content = listOf(Block.Text("截图完成"), Block.Image("ref", "image/png", 1, 1)),
                            isError = false,
                        ),
                    ),
                ),
            )

        val request = builder.build(emptyList(), messages, emptyList(), finalRound = false)

        val toolMsg = request.messages[1]
        assertThat(toolMsg.role).isEqualTo(Role.USER)
        assertThat(toolMsg.blocks.filterIsInstance<Block.Image>()).hasSize(1)
    }

    private fun builder(profile: ModelProfile): RequestBuilder =
        RequestBuilder(
            prompts = StubPrompts,
            profile = profile,
            thinking = AgentThinkingSection(enabled = true, budgetTokens = 1024),
            texts = texts,
        )

    private fun profile(
        tools: Boolean = true,
        vision: Boolean = true,
        thinking: Boolean = true,
    ): ModelProfile =
        ModelProfile(
            id = "p1",
            displayName = "p1",
            protocol = "anthropic",
            baseUrl = "",
            apiKeyRef = "",
            model = "m",
            contextWindow = 100000,
            maxOutputTokens = 1000,
            capabilities = ModelCapabilities(tools = tools, vision = vision, thinking = thinking),
        )

    private object StubPrompts : PromptRepository {
        override fun get(id: String): PromptTemplate = error("unused")

        override fun render(
            id: String,
            vars: Map<String, String>,
        ): Outcome<RenderedPrompt> = Outcome.Ok(RenderedPrompt(id = id, version = 1, text = "[$id]"))

        override fun all(): List<PromptTemplate> = emptyList()
    }
}
