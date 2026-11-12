package com.example.autop.core.config.impl

import com.example.autop.core.common.AutopJson
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/** 内置策略规则文件的加载与挂载（docs/tech/06 §6）。 */
class RuleFileLoaderTest {
    @Test
    fun `real rules asset loads with categories and rules`() {
        val rules = loadPolicyRules(realAsset())
        assertThat(rules["categories"]).isNotNull()
        assertThat(rules["rules"]).isNotNull()
    }

    @Test
    fun `wrong schema version is rejected`() {
        val file =
            ConfigAssetFile(
                "policy_rules.json",
                """{"schemaVersion": ${RULE_FILE_SCHEMA_VERSION + 1}, "categories": {}, "rules": []}""",
            )
        assertThrows(IllegalStateException::class.java) { loadPolicyRules(file) }
    }

    @Test
    fun `duplicate rule id is rejected`() {
        val doc =
            """
            {"schemaVersion": $RULE_FILE_SCHEMA_VERSION, "categories": {"payment": {"overridable": false, "reasonKey": "k"}},
             "rules": [
               {"id": "r1", "type": "text_any", "category": "payment", "scope": "TARGET", "params": {}},
               {"id": "r1", "type": "text_any", "category": "payment", "scope": "TARGET", "params": {}}
             ]}
            """.trimIndent()
        assertThrows(IllegalStateException::class.java) { loadPolicyRules(ConfigAssetFile("policy_rules.json", doc)) }
    }

    @Test
    fun `unknown category is rejected`() {
        val doc =
            """
            {"schemaVersion": $RULE_FILE_SCHEMA_VERSION, "categories": {"payment": {"overridable": false, "reasonKey": "k"}},
             "rules": [{"id": "r1", "type": "text_any", "category": "nope", "scope": "TARGET", "params": {}}]}
            """.trimIndent()
        assertThrows(IllegalStateException::class.java) { loadPolicyRules(ConfigAssetFile("policy_rules.json", doc)) }
    }

    @Test
    fun `unknown type is rejected when known types are given`() {
        val doc =
            """
            {"schemaVersion": $RULE_FILE_SCHEMA_VERSION, "categories": {"payment": {"overridable": false, "reasonKey": "k"}},
             "rules": [{"id": "r1", "type": "nope", "category": "payment", "scope": "TARGET", "params": {}}]}
            """.trimIndent()
        assertThrows(IllegalStateException::class.java) {
            loadPolicyRules(ConfigAssetFile("policy_rules.json", doc), knownTypes = setOf("text_any"))
        }
    }

    @Test
    fun `merge policy rules replaces builtin rules placeholder`() {
        val defaults =
            """
            {"policy": {"builtinRules": {}, "userRules": [], "blockedPackages": []}, "agent": {"x": 1}}
            """.trimIndent()
        val rules = loadPolicyRules(realAsset())
        val merged = mergePolicyRules(parseObject(defaults), rules)
        val policy = merged["policy"]?.jsonObject
        assertThat(policy?.get("builtinRules")).isEqualTo(rules)
        assertThat(policy?.get("userRules")).isNotNull()
        assertThat(merged["agent"]).isNotNull()
    }

    private fun parseObject(text: String): JsonObject = AutopJson.default.parseToJsonElement(text).jsonObject

    private fun realAsset(): ConfigAssetFile {
        val path = System.getProperty(RULES_PROPERTY) ?: error("$RULES_PROPERTY not set")
        return ConfigAssetFile("policy_rules.json", File(path).readText(Charsets.UTF_8))
    }

    private companion object {
        const val RULES_PROPERTY = "autop.policyRulesFile"
    }
}
