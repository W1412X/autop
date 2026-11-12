/**
 * 请求组装（agent-harness-overhaul WP6.11）：他模型的思考块被丢弃、图片原样保留、
 * 思考参数只在配置开启且模型 quirk `thinking=true` 时发送、工具能力为 false 时走文本回退并保留工具结果图片。
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
    private val texts = SanitizeTexts(emptyToolResult = "[空结果]")

    @Test
    fun `thinking from other profile is dropped`() {
        val builder = builder(profile(thinking = true))
        val msg =
            Msg(Role.ASSISTANT, listOf(Block.Thinking("推理", signature = "sig")), producedBy = "other")

        val request = builder.build(emptyList(), listOf(msg), emptyList(), finalRound = false)

        assertThat(request.messages).isEmpty()
    }

    @Test
    fun `images are kept for the model`() {
        val builder = builder(profile())
        val msg = Msg(Role.USER, listOf(Block.Image("ref", "image/png", 1, 1), Block.Text("看")))

        val request = builder.build(emptyList(), listOf(msg), emptyList(), finalRound = false)

        assertThat(
            request.messages
                .single()
                .blocks
                .filterIsInstance<Block.Image>(),
        ).hasSize(1)
    }

    @Test
    fun `thinking budget is sent only when config and quirk both enable it`() {
        val enabled = builder(profile(thinking = true)).build(emptyList(), emptyList(), emptyList(), false)
        val quirkOff = builder(profile(thinking = false)).build(emptyList(), emptyList(), emptyList(), false)

        assertThat(enabled.thinkingBudgetTokens).isEqualTo(THINKING_BUDGET)
        assertThat(quirkOff.thinkingBudgetTokens).isNull()
    }

    @Test
    fun `fallback profile sends no tools field and downgrades tool results keeping images`() {
        val builder = builder(profile(tools = false))
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

        val request = builder.build(emptyList(), messages, emptySchemas(), finalRound = false)

        assertThat(request.tools).isEmpty()
        val toolMsg = request.messages[1]
        assertThat(toolMsg.role).isEqualTo(Role.USER)
        assertThat(toolMsg.blocks.filterIsInstance<Block.Image>()).hasSize(1)
    }

    private fun emptySchemas() = emptyList<com.example.autop.llm.ToolSchema>()

    private fun builder(profile: ModelProfile): RequestBuilder =
        RequestBuilder(
            prompts = StubPrompts,
            profile = profile,
            thinking = AgentThinkingSection(enabled = true, budgetTokens = THINKING_BUDGET),
            texts = texts,
        )

    private fun profile(
        thinking: Boolean = false,
        tools: Boolean = true,
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
            capabilities = ModelCapabilities(tools = tools),
            quirks = if (thinking) mapOf("thinking" to "true") else emptyMap(),
        )

    private object StubPrompts : PromptRepository {
        override fun get(id: String): PromptTemplate = error("unused")

        override fun render(
            id: String,
            vars: Map<String, String>,
        ): Outcome<RenderedPrompt> = Outcome.Ok(RenderedPrompt(id = id, version = 1, text = "[$id]"))

        override fun all(): List<PromptTemplate> = emptyList()
    }

    private companion object {
        const val THINKING_BUDGET = 1024
    }
}
