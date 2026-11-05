/**
 * 策略判定结果（docs/tech/06 §5）：放行或拦截；拦截携带命中规则与原因键。
 */
package com.example.autop.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 策略判定。 */
@Serializable
public sealed interface PolicyDecision {
    /** 放行。 */
    @Serializable
    @SerialName("allow")
    public data object Allow : PolicyDecision

    /** 拦截：[ruleId] 命中规则，[category] 规则分类，[reasonKey] 原因文案键（对应提示模板/strings）。 */
    @Serializable
    @SerialName("block")
    public data class Block(
        @SerialName("ruleId") val ruleId: String,
        @SerialName("category") val category: String,
        @SerialName("reasonKey") val reasonKey: String,
    ) : PolicyDecision
}
