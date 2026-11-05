/**
 * 策略错误码（docs/tech/04 §3.3）：动作被策略拦截时的 `AppError.code`。
 */
package com.example.autop.policy

/** 策略错误码。 */
public object PolicyErrorCodes {
    /** 动作被策略规则拦截（hint 用模板 `agent.policy.blocked_hint`）。 */
    public const val POLICY_BLOCKED: String = "policy.blocked"
}
