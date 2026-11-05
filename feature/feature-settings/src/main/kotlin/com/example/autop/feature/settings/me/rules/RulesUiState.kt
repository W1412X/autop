/**
 * 安全规则页状态（docs/tech/08 §4.5 me/rules：内置规则只读列表 + 用户规则增删）。
 * 规则 JSON 形态见 docs/tech/06 §5 `PolicyRule`。
 */
package com.example.autop.feature.settings.me.rules

import com.example.autop.policy.PolicyRule
import com.example.autop.policy.RuleScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 列表中的一行规则。 */
public data class RuleRow(
    /** 规则 id；解析失败的行为 null。 */
    val id: String?,
    /** 规则类型（`type`），解析失败时为 null。 */
    val type: String?,
    /** 类别（`category`），解析失败时为 null。 */
    val category: String?,
    /** 作用范围（`scope`），解析失败时为 null。 */
    val scope: String?,
    /** 是否启用（`enabled`）。 */
    val enabled: Boolean,
    /** 解析失败的原始 JSON（可删除）。 */
    val raw: JsonObject? = null,
)

/** 编辑表单草稿（[RuleDraft.values] 等多值字段一行一个值）。 */
public data class RuleDraft(
    val id: String = "",
    val type: String = TYPE_TEXT_ANY,
    val category: String = "payment",
    val scope: String = "TARGET",
    /** 关键词 / 正则 / 协议（按 [type]）。 */
    val values: String = "",
    /** 包名（`text_any` 的 `packages`、`package` 类型的 `packages`）。 */
    val packages: String = "",
    /** 包名前缀（仅 `package` 类型）。 */
    val prefixes: String = "",
    /** 最少命中词数（仅 `text_any`，留空默认 1）。 */
    val minHits: String = "",
    val enabled: Boolean = true,
    /** 被编辑规则的原 id；null 表示新建。 */
    val originalId: String? = null,
) {
    /** 表单校验（[knownIds] 为已有规则 id，编辑时排除 [originalId]）。 */
    public fun validate(knownIds: Set<String>): RulesError? {
        val trimmed = id.trim()
        val taken = knownIds - setOfNotNull(originalId)
        val hits = minHits.trim().toIntOrNull()
        val minHitsBad = type == TYPE_TEXT_ANY && minHits.isNotBlank() && (hits == null || hits < 1)
        return when {
            trimmed.isEmpty() || trimmed in taken -> RulesError.ID_INVALID
            missingParams() -> RulesError.PARAMS_EMPTY
            minHitsBad -> RulesError.MIN_HITS_INVALID
            else -> null
        }
    }

    /** 草稿 → 规则对象（docs/tech/06 §5 `PolicyRule`）。 */
    public fun toRule(): PolicyRule =
        PolicyRule(
            id = id.trim(),
            type = type,
            category = category,
            scope = RuleScope.valueOf(scope),
            params = paramsOf(),
            enabled = enabled,
        )

    private fun missingParams(): Boolean =
        when (type) {
            "password_field" -> false
            "package" -> lines(packages).isEmpty() && lines(prefixes).isEmpty()
            else -> lines(values).isEmpty()
        }

    private fun paramsOf(): JsonObject =
        buildJsonObject {
            when (type) {
                "text_any" -> {
                    put("keywords", lineArray(values))
                    minHits.trim().toIntOrNull()?.let { hits -> put("minHits", JsonPrimitive(hits)) }
                    if (lines(packages).isNotEmpty()) put("packages", lineArray(packages))
                }
                "package" -> {
                    put("packages", lineArray(packages))
                    put("prefixes", lineArray(prefixes))
                }
                "activity_regex" -> put("patterns", lineArray(values))
                "uri_scheme" -> put("schemes", lineArray(values))
                "secure_with_text" -> put("keywords", lineArray(values))
            }
        }

    public companion object {
        /** `text_any` 类型值（docs/tech/06 §6）。 */
        public const val TYPE_TEXT_ANY: String = "text_any"

        /** 一行一个值 → 列表（去空白行）。 */
        public fun lines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        /** [lines] 的 JSON 数组形态。 */
        public fun lineArray(text: String): JsonArray =
            buildJsonArray { lines(text).forEach { add(JsonPrimitive(it)) } }
    }
}

/** 表单校验错误（文案在 feature-settings 的 strings，ViewModel 不接触资源）。 */
public enum class RulesError {
    ID_INVALID,
    PARAMS_EMPTY,
    MIN_HITS_INVALID,
    SAVE_FAILED,
}

public data class RulesUiState(
    /** 内置规则（只读）。 */
    val builtin: List<RuleRow> = emptyList(),
    /** 自定义规则（可增删改）。 */
    val user: List<RuleRow> = emptyList(),
    /** 打开的编辑表单；null 表示关闭。 */
    val editing: RuleDraft? = null,
    /** 表单校验错误；null 表示无。 */
    val error: RulesError? = null,
)
