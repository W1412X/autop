/**
 * 策略引擎端口（docs/tech/06 §5）：对写屏动作求值（放行或拦截），
 * 并决定哪些应用的窗口在观测中脱敏隐藏。
 */
package com.example.autop.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 策略引擎。 */
public interface PolicyEngine {
    /** 对 [subject] 求值；[overrides] 来自 Task 定义（`policy.overrides`）。 */
    public suspend fun evaluate(
        subject: PolicySubject,
        overrides: PolicyOverrides,
    ): PolicyDecision

    /** 屏蔽 App：true 时观测隐藏该窗口。 */
    public fun redaction(packageName: String?): Boolean
}

/** Task 级覆盖（一般只在 Task 覆盖层中设置）。 */
@Serializable
public data class PolicyOverrides(
    @SerialName("allowCategories") val allowCategories: Set<String> = emptySet(),
)
