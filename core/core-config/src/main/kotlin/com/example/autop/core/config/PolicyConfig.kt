/**
 * `policy` 领域的配置段与键（docs/tech/09 §1.5）。
 * 段默认值与 `core-config-impl/assets/config/policy.json` 必须一致（有测试）。
 *
 * `userRules` 用 `List<JsonObject>` 承载（见 questions.md N9）：强类型 `PolicyRule`
 * 解析留在 policy-core（WP5），配置层不倒依赖领域模型。
 */
package com.example.autop.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** `policy`：安全策略。 */
@Serializable
public data class PolicySection(
    /** 内置规则，运行时由 PolicyEngine 从 `assets/rules/policy_rules.json` 加载，只读。 */
    @SerialName("builtinRules") val builtinRules: JsonObject = JsonObject(emptyMap()),
    @SerialName("userRules") val userRules: List<JsonObject> = emptyList(),
    @SerialName("blockedPackages") val blockedPackages: List<String> = emptyList(),
    @SerialName("overrides") val overrides: Overrides = Overrides(),
    @SerialName("subject") val subject: Subject = Subject(),
) : ConfigSection {
    /** `policy.overrides`：Task 级覆盖（一般只在 Task 覆盖层中设置）。 */
    @Serializable
    public data class Overrides(
        @SerialName("allowCategories") val allowCategories: List<String> = emptyList(),
    )

    /** `policy.subject`：策略评估的屏幕主题参数。 */
    @Serializable
    public data class Subject(
        @SerialName("maxScreenTexts") val maxScreenTexts: Int = 80,
        @SerialName("secureCacheMs") val secureCacheMs: Long = 5000,
    )
}

/** `policy` 领域全部配置键的登记（DI 多绑定与测试用）。 */
public object PolicyConfig {
    public val POLICY: ConfigKey<PolicySection> =
        ConfigKey("policy", PolicySection.serializer()) { PolicySection() }

    public val ALL: List<ConfigKey<*>> = listOf(POLICY)
}
