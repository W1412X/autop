package com.example.autop.llm.anthropic

import com.example.autop.llm.ModelProfile
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnthropicQuirksTest {
    private val defaults =
        AnthropicQuirksDefaults(contextExceededPatterns = listOf("ctx"), imageMaxEdge = 1568, apiVersion = "2023-06-01")

    @Test
    fun `defaults apply when profile has no quirks`() {
        val quirks = AnthropicQuirks.of(profile(emptyMap()), defaults)

        assertThat(quirks.messagesPath).isEqualTo("/v1/messages")
        assertThat(quirks.toolChoiceNone).isEqualTo(ToolChoiceNoneMode.NATIVE)
        assertThat(quirks.contextExceededPatterns).containsExactly("ctx")
        assertThat(quirks.imageMaxEdge).isEqualTo(1568)
    }

    @Test
    fun `quirk values override defaults`() {
        val quirks =
            AnthropicQuirks.of(
                profile(
                    mapOf(
                        "messagesPath" to "/custom/messages",
                        "toolChoiceNone" to "omit_tools",
                        "contextExceededPatterns" to "too long| ctx2 ",
                        "imageMaxEdge" to "512",
                    ),
                ),
                defaults,
            )

        assertThat(quirks.messagesPath).isEqualTo("/custom/messages")
        assertThat(quirks.toolChoiceNone).isEqualTo(ToolChoiceNoneMode.OMIT_TOOLS)
        assertThat(quirks.contextExceededPatterns).containsExactly("too long", "ctx2").inOrder()
        assertThat(quirks.imageMaxEdge).isEqualTo(512)
    }

    @Test
    fun `blank context patterns fall back to defaults`() {
        val quirks = AnthropicQuirks.of(profile(mapOf("contextExceededPatterns" to " | ")), defaults)

        assertThat(quirks.contextExceededPatterns).containsExactly("ctx")
    }

    private fun profile(quirks: Map<String, String>): ModelProfile =
        ModelProfile(
            id = "p1",
            displayName = "P1",
            protocol = PROTOCOL_ID,
            baseUrl = "https://api.example.com",
            apiKeyRef = "secret:model:p1",
            model = "m",
            contextWindow = 1000,
            maxOutputTokens = 500,
            quirks = quirks,
        )
}
