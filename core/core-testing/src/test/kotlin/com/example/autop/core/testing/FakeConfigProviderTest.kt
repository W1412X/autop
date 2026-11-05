package com.example.autop.core.testing

import com.example.autop.core.common.AutopJson
import com.example.autop.core.config.UiConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/** 用真实 assets 默认值（autop.configDir）验证合并语义与流更新。 */
class FakeConfigProviderTest {
    @Test
    fun `real config tree decodes every registered key without problems`() {
        val provider = FakeConfigProvider()
        assertThat(provider.current().problems()).isEmpty()
        assertThat(provider.current().get(UiConfig.DEBUG).logBufferSize).isEqualTo(2000)
    }

    @Test
    fun `overrides merge on top of the real defaults`() {
        val provider = FakeConfigProvider(overlay("""{"ui":{"debug":{"logBufferSize":42}}}"""))
        assertThat(provider.current().get(UiConfig.DEBUG).logBufferSize).isEqualTo(42)
    }

    @Test
    fun `layered adds run level overlays without changing the global snapshot`() {
        val provider = FakeConfigProvider(overlay("""{"ui":{"debug":{"logBufferSize":42}}}"""))
        val layered = provider.layered(overlay("""{"ui":{"debug":{"logBufferSize":7}}}"""))
        assertThat(layered.get(UiConfig.DEBUG).logBufferSize).isEqualTo(7)
        assertThat(provider.current().get(UiConfig.DEBUG).logBufferSize).isEqualTo(42)
    }

    @Test
    fun `setOverrides emits a new snapshot on observe`() {
        val provider = FakeConfigProvider()
        assertThat(
            provider
                .observe()
                .value
                .get(UiConfig.DEBUG)
                .logBufferSize,
        ).isEqualTo(2000)
        provider.setOverrides(overlay("""{"ui":{"debug":{"logBufferSize":5}}}"""))
        assertThat(
            provider
                .observe()
                .value
                .get(UiConfig.DEBUG)
                .logBufferSize,
        ).isEqualTo(5)
        assertThat(provider.current().diffFromDefaults()).isNotNull()
    }

    private fun overlay(text: String): JsonObject = AutopJson.default.parseToJsonElement(text) as JsonObject
}
