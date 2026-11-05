/**
 * 规则文档解析（docs/tech/06 §6）：把 `policy.builtinRules`（内置规则文件整体）与
 * `policy.userRules` 解析为 [PolicyRuleSet]。未知类型的规则记录 warn 并跳过，不崩溃；
 * 单条用户规则解析失败同样 warn 并跳过。
 */
package com.example.autop.policy.core

import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Logger
import com.example.autop.policy.PolicyRule
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** 规则分类：能否被 Task 覆盖与原因文案键（docs/tech/06 §6）。 */
public data class PolicyCategory(
    val overridable: Boolean,
    val reasonKey: String,
)

/** 解析后的规则集合：内置与用户规则按“内置在前、用户在后”合并。 */
public data class PolicyRuleSet(
    val categories: Map<String, PolicyCategory>,
    val rules: List<PolicyRule>,
)

/** 规则文档解析器。 */
public object PolicyRuleParser {
    /**
     * 解析内置规则文档 [builtin]（`{schemaVersion, categories, rules}`）与用户规则 [userRules]；
     * 只保留 [knownTypes] 内且 `enabled` 的规则。
     */
    public fun parse(
        builtin: JsonObject,
        userRules: List<JsonObject>,
        knownTypes: Set<String>,
        logger: Logger,
    ): PolicyRuleSet {
        val categories = parseCategories(builtin)
        val rules = mutableListOf<PolicyRule>()
        parseRules(ruleElements(builtin["rules"]), rules, knownTypes, logger)
        parseRules(userRules, rules, knownTypes, logger)
        return PolicyRuleSet(categories, rules)
    }

    private fun parseCategories(builtin: JsonObject): Map<String, PolicyCategory> {
        val obj = builtin["categories"] as? JsonObject ?: return emptyMap()
        return obj.mapValues { (_, value) ->
            val entry = value as? JsonObject
            PolicyCategory(
                overridable = (entry?.get("overridable") as? JsonPrimitive)?.booleanOrNull ?: false,
                reasonKey = (entry?.get("reasonKey") as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }
    }

    private fun ruleElements(value: JsonElement?): List<JsonElement> = (value as? JsonArray)?.toList().orEmpty()

    private fun parseRules(
        elements: List<JsonElement>,
        out: MutableList<PolicyRule>,
        knownTypes: Set<String>,
        logger: Logger,
    ) {
        for (element in elements) {
            val rule = decodeRule(element, logger) ?: continue
            when {
                rule.type !in knownTypes ->
                    logger.w(TAG, "policy_rule_unknown_type", "id" to rule.id, "type" to rule.type)
                rule.enabled -> out += rule
            }
        }
    }

    private fun decodeRule(
        element: JsonElement,
        logger: Logger,
    ): PolicyRule? =
        try {
            AutopJson.default.decodeFromJsonElement(PolicyRule.serializer(), element)
        } catch (e: SerializationException) {
            logger.w(TAG, "policy_rule_invalid", error = e)
            null
        }

    private const val TAG = "PolicyRuleParser"
}
