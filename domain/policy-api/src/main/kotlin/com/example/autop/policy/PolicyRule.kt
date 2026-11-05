/**
 * 策略规则与规则类型扩展点（docs/tech/06 §5、§6）：规则以 JSON 存放于
 * `policy.builtinRules`（内置规则文件）与 `policy.userRules`（用户覆盖），
 * 匹配逻辑按 `type` 分发到 [PolicyRuleType] 实现。
 */
package com.example.autop.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 规则作用范围：TARGET 只看动作目标，SCREEN 看整个屏幕。 */
@Serializable
public enum class RuleScope {
    /** 只看动作目标。 */
    @SerialName("TARGET")
    TARGET,

    /** 看整个屏幕。 */
    @SerialName("SCREEN")
    SCREEN,
}

/** 一条策略规则。 */
@Serializable
public data class PolicyRule(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String,
    @SerialName("category") val category: String,
    @SerialName("scope") val scope: RuleScope,
    /** 适用的工具 id；空 = 所有 WRITE_SCREEN 工具。 */
    @SerialName("tools") val tools: List<String> = emptyList(),
    /** 各类型自己的参数。 */
    @SerialName("params") val params: JsonObject,
    @SerialName("enabled") val enabled: Boolean = true,
)

/** 规则类型扩展点：与规则 JSON 中的 `type` 对应。 */
public interface PolicyRuleType {
    /** 规则类型名（如 `text_any`）。 */
    public val type: String

    /** [subject] 是否命中 [rule]。 */
    public fun matches(
        rule: PolicyRule,
        subject: PolicySubject,
    ): Boolean
}
