package com.example.autop.flow

/** 流程领域错误码（docs/design/flows.md §17）。 */
public object FlowErrorCodes {
    public const val INVALID: String = "flow.invalid"
    public const val NOT_FOUND: String = "flow.not_found"
    public const val PARAM_MISSING: String = "flow.param_missing"
    public const val ARGS_INVALID: String = "flow.args_invalid"
    public const val EXPR_ERROR: String = "flow.expr_error"
    public const val VAR_UNDEFINED: String = "flow.var_undefined"
    public const val TYPE_ERROR: String = "flow.type_error"
    public const val VAR_TOO_LARGE: String = "flow.var_too_large"
    public const val TARGET_NOT_FOUND: String = "flow.target_not_found"
    public const val TARGET_AMBIGUOUS: String = "flow.target_ambiguous"
    public const val WAIT_TIMEOUT: String = "flow.wait_timeout"
    public const val LLM_UNAVAILABLE: String = "flow.llm_unavailable"
    public const val LLM_INVALID_OUTPUT: String = "flow.llm_invalid_output"
    public const val BUDGET_EXCEEDED: String = "flow.budget_exceeded"
    public const val AGENT_FAILED: String = "flow.agent_failed"
    public const val ENDPOINT_UNREGISTERED: String = "flow.endpoint_unregistered"
    public const val API_FAILED: String = "flow.api_failed"
    public const val API_STATUS: String = "flow.api_status"
    public const val DEPTH_EXCEEDED: String = "flow.depth_exceeded"
    public const val ENDED_FAILURE: String = "flow.ended_failure"
    public const val STATE_ERROR: String = "flow.state_error"
    public const val NODE_TIMEOUT: String = "flow.node_timeout"
    public const val CANCELLED: String = "flow.cancelled"
    public const val TOOL_FORBIDDEN: String = "flow.tool_forbidden"
    public const val MAX_DURATION: String = "flow.max_duration"

    /** 动作执行成功但期望效果未出现（屏幕无变化等）。 */
    public const val ACTION_NO_EFFECT: String = "flow.action_no_effect"

    /** 恢复阶梯全部用尽。 */
    public const val RECOVERY_EXHAUSTED: String = "flow.recovery_exhausted"

    /** 节点前置条件不满足，动作未执行。 */
    public const val PRECONDITION_FAILED: String = "flow.precondition_failed"

    /** 条件求值本身出错（非法正则、模板缺变量等），不属于条件不成立。 */
    public const val PRECONDITION_ERROR: String = "flow.precondition_error"

    /** 交回智能体后复核目标状态失败（子 Run 报成功但契约不成立）。 */
    public const val STATE_NOT_REACHED: String = "flow.state_not_reached"

    /** 结果契约（状态契约）不成立。 */
    public const val CONTRACT_UNMET: String = "flow.contract_unmet"
}
