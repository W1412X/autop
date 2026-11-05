/**
 * 规则 JSON ↔ 表单行/草稿的映射（docs/tech/06 §5 `PolicyRule`）。
 */
package com.example.autop.feature.settings.me.rules

import com.example.autop.core.common.AutopJson
import com.example.autop.policy.PolicyRule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 单个规则对象 → 列表行；解析失败保留 [RuleRow.raw]（可删除）。 */
internal fun rowOf(obj: JsonObject): RuleRow {
    val rule = runCatching { AutopJson.default.decodeFromJsonElement(PolicyRule.serializer(), obj) }.getOrNull()
    return if (rule == null) {
        RuleRow(id = null, type = null, category = null, scope = null, enabled = true, raw = obj)
    } else {
        RuleRow(
            id = rule.id,
            type = rule.type,
            category = rule.category,
            scope = rule.scope.name,
            enabled = rule.enabled,
        )
    }
}

/** 规则对象数组 → 列表行。 */
internal fun rowsOf(array: JsonArray?): List<RuleRow> =
    array?.mapNotNull { (it as? JsonObject)?.let(::rowOf) } ?: emptyList()

/** 规则对象 → 编辑草稿（反填表单字段）。 */
internal fun draftOf(rule: PolicyRule): RuleDraft =
    RuleDraft(
        id = rule.id,
        type = rule.type,
        category = rule.category,
        scope = rule.scope.name,
        values = valuesOf(rule),
        packages = strings(rule.params["packages"]).joinToString("\n"),
        prefixes = strings(rule.params["prefixes"]).joinToString("\n"),
        minHits = rule.params["minHits"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "",
        enabled = rule.enabled,
        originalId = rule.id,
    )

private fun valuesOf(rule: PolicyRule): String {
    val name =
        when (rule.type) {
            "activity_regex" -> "patterns"
            "uri_scheme" -> "schemes"
            else -> "keywords"
        }
    return strings(rule.params[name]).joinToString("\n")
}

private fun strings(element: kotlinx.serialization.json.JsonElement?): List<String> =
    (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
