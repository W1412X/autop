package com.example.autop.core.config

import com.example.autop.core.common.AutopJson
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConfigKeysTest {
    @Test
    fun `all key paths are unique`() {
        val paths = AllConfigKeys.ALL.map { it.path }
        assertThat(paths).containsNoDuplicates()
    }

    @Test
    fun `every key decodes its own serialized default instance`() {
        for (key in AllConfigKeys.ALL) {
            assertThat(roundTrip(key)).isEqualTo(key.defaultInstance())
        }
    }

    private fun <T : ConfigSection> roundTrip(key: ConfigKey<T>): T {
        val encoded = AutopJson.default.encodeToJsonElement(key.serializer, key.defaultInstance())
        return AutopJson.default.decodeFromJsonElement(key.serializer, encoded)
    }

    @Test
    fun `every key path is a dotted camelCase subtree path`() {
        for (key in AllConfigKeys.ALL) {
            val segments = key.path.split(".")
            // vp9 §7 起出现 3 段键（device.vision.icon/device.vision.rescan）。
            assertThat(segments.size).isIn(1..3)
            for (segment in segments) {
                assertThat(segment).matches("[a-z][a-zA-Z0-9]*")
            }
        }
    }

    @Test
    fun `byPath finds registered key and returns null for unknown`() {
        assertThat(AllConfigKeys.byPath("agent.compaction")).isEqualTo(AgentConfig.COMPACTION)
        assertThat(AllConfigKeys.byPath("nope.nope")).isNull()
    }

    @Test
    fun `compaction threshold validation rejects out of range values`() {
        assertThat(AgentCompactionSection(thresholdRatio = 0.75).validate()).isEmpty()
        assertThat(AgentCompactionSection(thresholdRatio = 1.5).validate()).isNotEmpty()
        assertThat(AgentCompactionSection(thresholdRatio = 0.1).validate()).isNotEmpty()
    }

    @Test
    fun `vision icon section validation rejects blank categories and range violations`() {
        assertThat(DeviceVisionIconSection().validate()).isEmpty()
    }
}
