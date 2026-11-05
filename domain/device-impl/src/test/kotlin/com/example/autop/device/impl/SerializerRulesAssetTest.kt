package com.example.autop.device.impl

import com.example.autop.core.common.AutopJson
import com.example.autop.device.core.SerializerRules
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 真实资产 `assets/rules/serializer_rules.json` 的守卫（docs/design/observation-modes-coordinate-actions §3.3、
 * docs/tech/09 §2.4）：新方案 schema v4 删除了 roles/marks/defaultRole，保留 tags/visualTags/keepContainerTags。
 */
class SerializerRulesAssetTest {
    @Test
    fun `rules asset decodes with schema version 4`() {
        val rules = load()
        assertThat(rules.schemaVersion).isEqualTo(4)
    }

    @Test
    fun `tag table covers visual and a11y suffixes`() {
        val rules = load()
        assertThat(rules.tags.first { it.classSuffix == "visual.ImageButton" }.tag).isEqualTo("icon")
        assertThat(rules.tags.first { it.classSuffix == "ImageButton" }.tag).isEqualTo("btn")
        assertThat(rules.tags.first { it.classSuffix == "TextView" }.tag).isEqualTo("text")
        assertThat(rules.tags.map { it.tag }).contains("tabbar")
        assertThat(rules.defaultTag?.interactive).isEqualTo("ctl")
        assertThat(rules.defaultTag?.label).isEqualTo("text")
        assertThat(rules.defaultTag?.container).isEqualTo("group")
        assertThat(rules.keepContainerTags).contains("tabbar")
    }

    @Test
    fun `visual tag vocabulary and window labels are populated`() {
        val rules = load()
        assertThat(rules.visualTags).containsAtLeast("text", "icon", "btn", "input", "list")
        assertThat(rules.windowTypeLabels).isNotEmpty()
        assertThat(rules.semanticPoorClasses).isNotEmpty()
        assertThat(rules.viewIdPrefixes).contains("btn_")
        assertThat(rules.viewIdNoise).isNotEmpty()
        assertThat(rules.volatileLabelPatterns).isNotEmpty()
    }

    private fun load(): SerializerRules {
        val path = System.getProperty(RULES_PROPERTY) ?: error("$RULES_PROPERTY not set")
        val json = File(path).readText(Charsets.UTF_8)
        return AutopJson.default.decodeFromString(SerializerRules.serializer(), json)
    }

    private companion object {
        const val RULES_PROPERTY = "autop.serializerRulesFile"
    }
}
