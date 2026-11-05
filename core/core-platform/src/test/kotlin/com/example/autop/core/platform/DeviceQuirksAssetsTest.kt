package com.example.autop.core.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** 校验真实的 `assets/rules/device_quirks.json`（路径由 Gradle 属性 `autop.quirksFile` 传入）。 */
class DeviceQuirksAssetsTest {
    private val rulesFile = File(System.getProperty("autop.quirksFile") ?: error("autop.quirksFile not set"))

    @Test
    fun `real rules file parses and passes schema validation`() {
        val rules = parseDeviceQuirksRules(rulesFile.readText())
        assertThat(rules.schemaVersion).isEqualTo(CURRENT_DEVICE_QUIRKS_SCHEMA_VERSION)
        assertThat(rules.quirks).isNotEmpty()
    }

    @Test
    fun `real rules file has unique ids and lowercase manufacturers`() {
        val rules = parseDeviceQuirksRules(rulesFile.readText())
        val ids = rules.quirks.map { it.id }
        assertThat(ids).containsNoDuplicates()
        for (quirk in rules.quirks) {
            for (maker in quirk.match.manufacturer) {
                assertThat(maker).isEqualTo(maker.lowercase())
            }
        }
    }

    @Test
    fun `every quirk carries autostart intent and background guide key`() {
        val rules = parseDeviceQuirksRules(rulesFile.readText())
        for (quirk in rules.quirks) {
            assertThat(quirk.values.keys).containsAtLeast("autostartIntent", "backgroundGuideKey")
            assertThat(quirk.values.getValue("autostartIntent")).startsWith("component:")
            assertThat(quirk.values.getValue("backgroundGuideKey")).isNotEmpty()
        }
    }

    @Test
    fun `xiaomi entry matches a redmi device and exposes both values`() {
        val rules = parseDeviceQuirksRules(rulesFile.readText())
        val quirks = DeviceQuirksImpl(rules, manufacturer = "Redmi", sdkInt = 30)
        assertThat(quirks.value("autostartIntent")).contains("miui")
        assertThat(quirks.value("backgroundGuideKey")).isEqualTo("guide_xiaomi")
    }
}
