/**
 * `agent` 领域的配置段与键（docs/tech/09 §1.1）。
 * 段默认值与 `core-config-impl/assets/config/agent.json` 必须一致（有测试）。
 */
package com.example.autop.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `agent.context`：上下文片段装配顺序。 */
@Serializable
public data class AgentContextSection(
    @SerialName("contributors") val contributors: List<String> = DEFAULT_CONTRIBUTORS,
) : ConfigSection {
    public companion object {
        public val DEFAULT_CONTRIBUTORS: List<String> =
            listOf(
                "context.role",
                "context.safety",
                "context.tool_guide",
                "context.run_mode",
                "context.memory_index",
                "context.user_profile",
                "context.environment",
                "context.flow_directory",
                "context.trigger",
                "context.trigger_memory",
                "context.goal",
            )
    }
}

/** `agent.tools`：启用的工具与超时。 */
@Serializable
public data class AgentToolsSection(
    @SerialName("enabled") val enabled: List<String> = DEFAULT_ENABLED,
    @SerialName("defaultTimeoutMs") val defaultTimeoutMs: Long = 30000,
    @SerialName("timeoutMs") val timeoutMs: Map<String, Long> = DEFAULT_TIMEOUT_MS,
) : ConfigSection {
    public companion object {
        public val DEFAULT_ENABLED: List<String> =
            listOf(
                "tool.observe",
                "tool.screenshot",
                "tool.tap",
                "tool.input_text",
                "tool.scroll",
                "tool.swipe",
                "tool.global_action",
                "tool.open_app",
                "tool.open_uri",
                "tool.wait",
                "tool.dismiss_popup",
                "tool.restart_app",
                "tool.list_apps",
                "tool.read_notifications",
                "tool.device_state",
                "tool.clipboard_get",
                "tool.clipboard_set",
                "tool.notify_user",
                "tool.memory_list",
                "tool.memory_read",
                "tool.memory_search",
                "tool.memory_write",
                "tool.update_plan",
                "tool.finish",
                "tool.ask_user",
                "tool.history_recall",
                "tool.note",
                "tool.run_flow",
                "tool.list_flows",
                "tool.save_as_flow",
                "tool.update_flow",
                "tool.patch_flow_node",
                "tool.create_flow",
                "tool.edit_flow",
                "tool.list_tasks",
                "tool.create_task",
                "tool.update_task",
            )

        public val DEFAULT_TIMEOUT_MS: Map<String, Long> =
            mapOf(
                // observe 的 llm 模式单次请求上限 120s、最多重试 1 次（240s），工具级预算要盖住它，
                // 否则路由先超时，agent 拿到的不是 device.llm_parse_failed 而是 agent.tool_timeout。
                "tool.observe" to 300000,
                "tool.wait" to 120000,
                "tool.ask_user" to 600000,
                // 流程执行可能远超默认 30s，预算要盖住整段流程运行（flows.md §14.1），
                // 否则 withTimeout 会取消整个流程会话并返回 agent.tool_timeout。
                "tool.run_flow" to 600000,
            )
    }
}

/** `agent.history`：历史渲染规则与截断参数。 */
@Serializable
public data class AgentHistorySection(
    @SerialName("rules") val rules: List<String> = DEFAULT_RULES,
    @SerialName("observation") val observation: Observation = Observation(),
    @SerialName("image") val image: Image = Image(),
    @SerialName("truncate") val truncate: Truncate = Truncate(),
) : ConfigSection {
    /** `agent.history.observation`：观测降级。 */
    @Serializable
    public data class Observation(
        /** 保留原文的观测个数；≤0 不降级（N209）。 */
        @SerialName("keepFull") val keepFull: Int = -1,
    )

    /** `agent.history.image`：图片保留。 */
    @Serializable
    public data class Image(
        @SerialName("keep") val keep: Int = 1,
    )

    /** `agent.history.truncate`：长文本截断。 */
    @Serializable
    public data class Truncate(
        /** 触发截断的块长；≤0 不截断（N209）。 */
        @SerialName("maxChars") val maxChars: Int = -1,
        @SerialName("headChars") val headChars: Int = 2500,
        @SerialName("tailChars") val tailChars: Int = 1500,
    )

    public companion object {
        public val DEFAULT_RULES: List<String> =
            listOf("history.observation_degrade", "history.image_retention", "history.long_text_truncate")
    }
}

/** `agent.tokens`：token 估算参数。 */
@Serializable
public data class AgentTokensSection(
    @SerialName("cjkFactor") val cjkFactor: Double = 1.5,
    @SerialName("charsPerToken") val charsPerToken: Double = 4.0,
    @SerialName("imageTokens") val imageTokens: Int = 1000,
    @SerialName("perMessageOverhead") val perMessageOverhead: Int = 4,
) : ConfigSection

/** `agent.compaction`：上下文压缩。thresholdRatio 文档约束范围 0.3–0.95。 */
@Serializable
public data class AgentCompactionSection(
    @SerialName("thresholdRatio") val thresholdRatio: Double = 0.75,
    @SerialName("strategies") val strategies: List<String> = DEFAULT_STRATEGIES,
    @SerialName("retries") val retries: Int = 2,
    @SerialName("maxConsecutiveFailures") val maxConsecutiveFailures: Int = 3,
    @SerialName("keepUserMessageTokens") val keepUserMessageTokens: Int = 4000,
    @SerialName("deterministicRecentCalls") val deterministicRecentCalls: Int = 12,
) : ConfigSection {
    override fun validate(): List<String> =
        if (thresholdRatio in THRESHOLD_MIN..THRESHOLD_MAX) {
            emptyList()
        } else {
            listOf("thresholdRatio must be within $THRESHOLD_MIN..$THRESHOLD_MAX, was $thresholdRatio")
        }

    public companion object {
        public const val THRESHOLD_MIN: Double = 0.3
        public const val THRESHOLD_MAX: Double = 0.95
        public val DEFAULT_STRATEGIES: List<String> = listOf("compaction.handoff", "compaction.deterministic")
    }
}

/**
 * `agent.budget`：单次运行的预算。各维度正数=上限，0（或负数）=不限；
 * 默认全部不限（开箱不限制，需要限制时配正数）。
 */
@Serializable
public data class AgentBudgetSection(
    @SerialName("maxSteps") val maxSteps: Int = 0,
    @SerialName("maxTotalTokens") val maxTotalTokens: Long = 0,
    @SerialName("maxDurationMs") val maxDurationMs: Long = 0,
    @SerialName("maxScreenshots") val maxScreenshots: Int = 0,
) : ConfigSection {
    override fun validate(): List<String> =
        listOfNotNull(
            "maxSteps must be >= 0, was $maxSteps".takeIf { maxSteps < 0 },
            "maxTotalTokens must be >= 0, was $maxTotalTokens".takeIf { maxTotalTokens < 0 },
            "maxDurationMs must be >= 0, was $maxDurationMs".takeIf { maxDurationMs < 0 },
            "maxScreenshots must be >= 0, was $maxScreenshots".takeIf { maxScreenshots < 0 },
        )
}

/** `agent.breaker`：熔断阈值。 */
@Serializable
public data class AgentBreakerSection(
    @SerialName("sameErrorWarn") val sameErrorWarn: Int = 3,
    @SerialName("sameErrorStop") val sameErrorStop: Int = 5,
    @SerialName("stuckWarn") val stuckWarn: Int = 3,
    @SerialName("stuckStop") val stuckStop: Int = 6,
    @SerialName("repeatWarn") val repeatWarn: Int = 3,
    @SerialName("repeatStop") val repeatStop: Int = 5,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (sameErrorWarn < 1) add("sameErrorWarn must be >= 1, was $sameErrorWarn")
            if (sameErrorWarn >= sameErrorStop) {
                add("sameErrorWarn must be < sameErrorStop, was $sameErrorWarn >= $sameErrorStop")
            }
            if (stuckWarn < 1) add("stuckWarn must be >= 1, was $stuckWarn")
            if (stuckWarn >= stuckStop) add("stuckWarn must be < stuckStop, was $stuckWarn >= $stuckStop")
            if (repeatWarn < 1) add("repeatWarn must be >= 1, was $repeatWarn")
            if (repeatWarn >= repeatStop) add("repeatWarn must be < repeatStop, was $repeatWarn >= $repeatStop")
        }
}

/** `agent.plan`：计划行为。 */
@Serializable
public data class AgentPlanSection(
    @SerialName("blockFinishOnce") val blockFinishOnce: Boolean = true,
) : ConfigSection

/** `agent.thinking`：模型思考（推理）开关（默认开：思考流式展示、落定折叠，N172）。 */
@Serializable
public data class AgentThinkingSection(
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("budgetTokens") val budgetTokens: Int = 2048,
) : ConfigSection

/** `agent.fallback`：JSON 回退协议。 */
@Serializable
public data class AgentFallbackSection(
    @SerialName("parseRetries") val parseRetries: Int = 2,
) : ConfigSection

/** `agent.recall`：历史检索（RRF 融合）。 */
@Serializable
public data class AgentRecallSection(
    @SerialName("maxChars") val maxChars: Int = 4000,
    @SerialName("channels") val channels: List<String> = DEFAULT_CHANNELS,
    @SerialName("weights") val weights: Map<String, Double> = mapOf("recall.fts" to 1.0, "recall.vector" to 1.0),
    @SerialName("rrfK") val rrfK: Int = 60,
    @SerialName("perChannelLimit") val perChannelLimit: Int = 20,
    @SerialName("defaultK") val defaultK: Int = 8,
    @SerialName("snippetChars") val snippetChars: Int = 80,
    @SerialName("vector") val vector: Vector = Vector(),
    @SerialName("indexQueueCapacity") val indexQueueCapacity: Int = 500,
) : ConfigSection {
    /** `agent.recall.vector`：向量通道切块。 */
    @Serializable
    public data class Vector(
        @SerialName("chunkChars") val chunkChars: Int = 400,
    )

    public companion object {
        public val DEFAULT_CHANNELS: List<String> = listOf("recall.fts", "recall.vector")
    }
}

/** `agent.ui`：运行界面回放。 */
@Serializable
public data class AgentUiSection(
    @SerialName("eventReplay") val eventReplay: Int = 64,
) : ConfigSection

/** `agent.store`：事件存储。 */
@Serializable
public data class AgentStoreSection(
    @SerialName("inlineMaxChars") val inlineMaxChars: Int = 65536,
    @SerialName("ftsToolResultChars") val ftsToolResultChars: Int = 500,
) : ConfigSection

/** `agent.notes`：运行笔记（R1/B3）的渲染限制。 */
@Serializable
public data class AgentNotesSection(
    @SerialName("maxItems") val maxItems: Int = 20,
    @SerialName("maxTokens") val maxTokens: Int = 800,
    @SerialName("maxCharsPerNote") val maxCharsPerNote: Int = 400,
) : ConfigSection

/** `agent` 领域全部配置键的登记（DI 多绑定与测试用）。 */
public object AgentConfig {
    public val CONTEXT: ConfigKey<AgentContextSection> =
        ConfigKey("agent.context", AgentContextSection.serializer()) { AgentContextSection() }
    public val TOOLS: ConfigKey<AgentToolsSection> =
        ConfigKey("agent.tools", AgentToolsSection.serializer()) { AgentToolsSection() }
    public val HISTORY: ConfigKey<AgentHistorySection> =
        ConfigKey("agent.history", AgentHistorySection.serializer()) { AgentHistorySection() }
    public val TOKENS: ConfigKey<AgentTokensSection> =
        ConfigKey("agent.tokens", AgentTokensSection.serializer()) { AgentTokensSection() }
    public val COMPACTION: ConfigKey<AgentCompactionSection> =
        ConfigKey("agent.compaction", AgentCompactionSection.serializer()) { AgentCompactionSection() }
    public val BUDGET: ConfigKey<AgentBudgetSection> =
        ConfigKey("agent.budget", AgentBudgetSection.serializer()) { AgentBudgetSection() }
    public val BREAKER: ConfigKey<AgentBreakerSection> =
        ConfigKey("agent.breaker", AgentBreakerSection.serializer()) { AgentBreakerSection() }
    public val PLAN: ConfigKey<AgentPlanSection> =
        ConfigKey("agent.plan", AgentPlanSection.serializer()) { AgentPlanSection() }
    public val THINKING: ConfigKey<AgentThinkingSection> =
        ConfigKey("agent.thinking", AgentThinkingSection.serializer()) { AgentThinkingSection() }
    public val FALLBACK: ConfigKey<AgentFallbackSection> =
        ConfigKey("agent.fallback", AgentFallbackSection.serializer()) { AgentFallbackSection() }
    public val RECALL: ConfigKey<AgentRecallSection> =
        ConfigKey("agent.recall", AgentRecallSection.serializer()) { AgentRecallSection() }
    public val UI: ConfigKey<AgentUiSection> =
        ConfigKey("agent.ui", AgentUiSection.serializer()) { AgentUiSection() }
    public val STORE: ConfigKey<AgentStoreSection> =
        ConfigKey("agent.store", AgentStoreSection.serializer()) { AgentStoreSection() }
    public val NOTES: ConfigKey<AgentNotesSection> =
        ConfigKey("agent.notes", AgentNotesSection.serializer()) { AgentNotesSection() }

    public val ALL: List<ConfigKey<*>> =
        listOf(
            CONTEXT,
            TOOLS,
            HISTORY,
            TOKENS,
            COMPACTION,
            BUDGET,
            BREAKER,
            PLAN,
            THINKING,
            FALLBACK,
            RECALL,
            UI,
            STORE,
            NOTES,
        )
}
