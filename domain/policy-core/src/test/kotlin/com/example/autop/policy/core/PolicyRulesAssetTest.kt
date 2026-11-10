package com.example.autop.policy.core

import com.example.autop.core.common.AutopJson
import com.example.autop.policy.PolicyRule
import com.example.autop.policy.RuleScope
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Test
import java.io.File

/** 真实资产 `assets/rules/policy_rules.json` 的守护（docs/tech/06 §6、docs/tech/09 §3）：能解析、无重复 id、引用的类别/类型都存在。 */
class PolicyRulesAssetTest {
    @Test
    fun `asset decodes with schema version and category table`() {
        val doc = load()
        assertThat((doc["schemaVersion"] as JsonPrimitive).contentOrNull).isEqualTo("4")
        val categories = checkNotNull(doc["categories"] as? JsonObject)
        assertThat(categories.keys).containsAtLeast("payment", "password", "system_security")
        val payment = checkNotNull(categories["payment"] as? JsonObject)
        assertThat(payment["overridable"].toString()).isEqualTo("false")
        val sysSecurity = checkNotNull(categories["system_security"] as? JsonObject)
        assertThat(sysSecurity["overridable"].toString()).isEqualTo("true")
    }

    @Test
    fun `rule ids are unique and category references exist`() {
        val doc = load()
        val categories = checkNotNull(doc["categories"] as? JsonObject).keys
        val rules = ruleObjects(doc)
        assertThat(rules.map { it.id }).containsNoDuplicates()
        rules.forEach { rule -> assertThat(categories).contains(rule.category) }
    }

    @Test
    fun `rule types reference builtin types and scopes are valid`() {
        val builtinTypes = BuiltinRuleTypes.ALL.map { it.type }
        ruleObjects(load()).forEach { rule ->
            assertThat(builtinTypes).contains(rule.type)
            assertThat(rule.scope).isAnyOf(RuleScope.TARGET, RuleScope.SCREEN)
        }
    }

    @Test
    fun `parser keeps all builtin rules without warnings`() {
        val logger = RecordingLogger()
        val parsed = PolicyRuleParser.parse(load(), emptyList(), BuiltinRuleTypes.ALL.map { it.type }.toSet(), logger)
        assertThat(parsed.rules).hasSize(ruleObjects(load()).size)
        assertThat(parsed.categories).isNotEmpty()
        assertThat(logger.warnEvents).isEmpty()
    }

    private fun ruleObjects(doc: JsonObject): List<PolicyRule> {
        val array = checkNotNull(doc["rules"] as? JsonArray)
        return array.map { AutopJson.default.decodeFromJsonElement(PolicyRule.serializer(), it) }
    }

    private fun load(): JsonObject {
        val path = System.getProperty(RULES_PROPERTY) ?: error("$RULES_PROPERTY not set")
        val text = File(path).readText(Charsets.UTF_8)
        return AutopJson.default.parseToJsonElement(text) as JsonObject
    }

    private companion object {
        const val RULES_PROPERTY = "autop.policyRulesFile"
    }
}
