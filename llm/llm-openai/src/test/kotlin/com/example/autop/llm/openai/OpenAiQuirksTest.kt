package com.example.autop.llm.openai

import com.example.autop.llm.ModelProfile
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenAiQuirksTest {
    private val defaults = OpenAiQuirksDefaults(contextExceededPatterns = listOf("ctx"), imageMaxEdge = 1568)

    @Test
    fun `defaults apply when profile has no quirks`() {
        val quirks = OpenAiQuirks.of(profile(quirks = emptyMap()), defaults)

        assertThat(quirks.chatPath).isEqualTo("/chat/completions")
        assertThat(quirks.maxTokensField).isEqualTo("max_tokens")
        assertThat(quirks.reasoningField).isEqualTo("reasoning_content")
        assertThat(quirks.contextExceededPatterns).containsExactly("ctx")
    }

    @Test
    fun `quirk values override defaults`() {
        val quirks =
            OpenAiQuirks.of(
                profile(
                    mapOf(
                        "chatPath" to "/v1/chat",
                        "maxTokensField" to "max_completion_tokens",
                        "contextExceededPatterns" to "too long| ctx2 ",
                        "imageMaxEdge" to "512",
                    ),
                ),
                defaults,
            )

        assertThat(quirks.chatPath).isEqualTo("/v1/chat")
        assertThat(quirks.maxTokensField).isEqualTo("max_completion_tokens")
        assertThat(quirks.contextExceededPatterns).containsExactly("too long", "ctx2").inOrder()
        assertThat(quirks.imageMaxEdge).isEqualTo(512)
    }

    @Test
    fun `blank context patterns fall back to defaults`() {
        val quirks = OpenAiQuirks.of(profile(mapOf("contextExceededPatterns" to " | ")), defaults)

        assertThat(quirks.contextExceededPatterns).containsExactly("ctx")
    }

    private fun profile(quirks: Map<String, String>): ModelProfile =
        ModelProfile(
            id = "p1",
            displayName = "P1",
            protocol = PROTOCOL_ID,
            baseUrl = "https://api.example.com/v1",
            apiKeyRef = "secret:model:p1",
            model = "m",
            contextWindow = 1000,
            maxOutputTokens = 500,
            quirks = quirks,
        )
}
