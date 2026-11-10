/**
 * agent 域错误码（docs/tech/04 §5）。
 */
package com.example.autop.agent

public object AgentErrorCodes {
    public const val UNKNOWN_TOOL: String = "agent.unknown_tool"
    public const val INVALID_ARGS_JSON: String = "agent.invalid_args_json"
    public const val INVALID_ARGS: String = "agent.invalid_args"
    public const val TOOL_TIMEOUT: String = "agent.tool_timeout"
    public const val TOOL_CRASHED: String = "agent.tool_crashed"
    public const val SKIPPED_PREVIOUS_FAILED: String = "agent.skipped_previous_failed"
    public const val SKIPPED_AFTER_CONTROL: String = "agent.skipped_after_control"
    public const val MODEL_REFUSED: String = "agent.model_refused"
    public const val OUTPUT_TRUNCATED: String = "agent.output_truncated"
    public const val EMPTY_REPLY: String = "agent.empty_reply"
    public const val ASK_TIMEOUT: String = "agent.ask_timeout"
    public const val COMPACTION_FAILED: String = "agent.compaction_failed"
    public const val FALLBACK_PARSE_FAILED: String = "agent.fallback_parse_failed"
    public const val BUDGET_EXHAUSTED: String = "agent.budget_exhausted"
    public const val BREAKER_STOPPED: String = "agent.breaker_stopped"
    public const val REPLAY_STATE_MISMATCH: String = "agent.replay_state_mismatch"
    public const val REPLAY_STEP_FAILED: String = "agent.replay_step_failed"
}
