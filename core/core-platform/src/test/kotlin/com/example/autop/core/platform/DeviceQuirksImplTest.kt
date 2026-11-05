package com.example.autop.core.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceQuirksImplTest {
    private val rules =
        DeviceQuirksRules(
            schemaVersion = 1,
            quirks =
                listOf(
                    DeviceQuirk(
                        id = "all.min30",
                        match = DeviceQuirkMatch(manufacturer = emptyList(), minSdk = 30),
                        values = mapOf("a" to "from-all", "b" to "from-all"),
                    ),
                    DeviceQuirk(
                        id = "xiaomi.autostart",
                        match = DeviceQuirkMatch(manufacturer = listOf("xiaomi", "redmi"), minSdk = 30),
                        values = mapOf("b" to "from-xiaomi", "c" to "from-xiaomi"),
                    ),
                    DeviceQuirk(
                        id = "xiaomi.high",
                        match = DeviceQuirkMatch(manufacturer = listOf("xiaomi"), minSdk = 33),
                        values = mapOf("c" to "from-high-sdk"),
                    ),
                ),
        )

    @Test
    fun `manufacturer match is case insensitive`() {
        val quirks = DeviceQuirksImpl(rules, manufacturer = "Xiaomi", sdkInt = 34)
        assertThat(quirks.value("c")).isEqualTo("from-high-sdk")
    }

    @Test
    fun `later matching quirk overrides earlier values in file order`() {
        val quirks = DeviceQuirksImpl(rules, manufacturer = "Xiaomi", sdkInt = 34)
        assertThat(quirks.value("a")).isEqualTo("from-all")
        assertThat(quirks.value("b")).isEqualTo("from-xiaomi")
        assertThat(quirks.value("c")).isEqualTo("from-high-sdk")
    }

    @Test
    fun `min sdk filters out higher sdk rules`() {
        val quirks = DeviceQuirksImpl(rules, manufacturer = "redmi", sdkInt = 30)
        assertThat(quirks.value("b")).isEqualTo("from-xiaomi")
        assertThat(quirks.value("c")).isEqualTo("from-xiaomi")
    }

    @Test
    fun `non matching manufacturer only gets the universal rule`() {
        val quirks = DeviceQuirksImpl(rules, manufacturer = "Google", sdkInt = 34)
        assertThat(quirks.value("a")).isEqualTo("from-all")
        assertThat(quirks.value("c")).isNull()
    }

    @Test
    fun `unknown key returns null`() {
        val quirks = DeviceQuirksImpl(rules, manufacturer = "Xiaomi", sdkInt = 34)
        assertThat(quirks.value("ghost")).isNull()
    }
}
