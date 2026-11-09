/**
 * `flow` 领域的配置段与键（docs/design/flows.md §15）。
 * 段默认值与 `core-config-impl/assets/config/flow.json` 必须一致（有测试）。
 * 取值 ≤0 表示不限（除非注释另有说明）。
 */
package com.example.autop.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `flow.run`：解释器运行参数。 */
@Serializable
public data class FlowRunSection(
    /** a11y 观测的轮询间隔。 */
    @SerialName("pollMs") val pollMs: Long = 500,
    /** visual / llm 观测的轮询最小间隔。 */
    @SerialName("slowPollMs") val slowPollMs: Long = 2000,
    /** `wait` 节点的默认超时。 */
    @SerialName("waitTimeoutMs") val waitTimeoutMs: Long = 10000,
    /** 目标解析的等待上限（0 = 不等）。 */
    @SerialName("targetWaitMs") val targetWaitMs: Long = 3000,
    /** 观测缓存有效期（0 = 不缓存）。 */
    @SerialName("observeCacheMs") val observeCacheMs: Long = 300,
    /** 表达式求值步数上限。 */
    @SerialName("exprMaxSteps") val exprMaxSteps: Int = 10000,
    /** 单个变量序列化后的大小上限（字节）。 */
    @SerialName("maxVarBytes") val maxVarBytes: Int = 1048576,
    /** 子流程嵌套的技术保护值（非产品层深度限制）。 */
    @SerialName("depthGuard") val depthGuard: Int = 200,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (pollMs <= 0) add("pollMs must be > 0, was $pollMs")
            if (slowPollMs <= 0) add("slowPollMs must be > 0, was $slowPollMs")
            if (waitTimeoutMs < 0) add("waitTimeoutMs must be >= 0, was $waitTimeoutMs")
            if (targetWaitMs < 0) add("targetWaitMs must be >= 0, was $targetWaitMs")
            if (observeCacheMs < 0) add("observeCacheMs must be >= 0, was $observeCacheMs")
            if (exprMaxSteps <= 0) add("exprMaxSteps must be > 0, was $exprMaxSteps")
            if (maxVarBytes <= 0) add("maxVarBytes must be > 0, was $maxVarBytes")
            if (depthGuard <= 0) add("depthGuard must be > 0, was $depthGuard")
        }
}

/** `flow.llm`：LLM 节点默认参数。 */
@Serializable
public data class FlowLlmSection(
    /** 校验失败时的默认重试次数。 */
    @SerialName("retries") val retries: Int = 2,
    /** 未指定时的单次输出 token 上限。 */
    @SerialName("maxOutputTokens") val maxOutputTokens: Int = 1024,
    /** 每日 LLM token 预算（输入+输出），≤0 不限。 */
    @SerialName("dailyTokenBudget") val dailyTokenBudget: Long = 0,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (retries < 0) add("retries must be >= 0, was $retries")
            if (maxOutputTokens <= 0) add("maxOutputTokens must be > 0, was $maxOutputTokens")
        }
}

/** `flow.agent`：智能体节点默认参数。 */
@Serializable
public data class FlowAgentSection(
    @SerialName("maxSteps") val maxSteps: Int = 20,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (maxSteps <= 0) add("maxSteps must be > 0, was $maxSteps")
        }
}

/** `flow.api`：API 节点参数。 */
@Serializable
public data class FlowApiSection(
    @SerialName("timeoutMs") val timeoutMs: Long = 30000,
    @SerialName("maxRequestBytes") val maxRequestBytes: Int = 1048576,
    @SerialName("maxResponseBytes") val maxResponseBytes: Int = 2097152,
    /** 允许 `http://` 接口（仅局域网/本机调试）；默认只接受 https。 */
    @SerialName("allowHttp") val allowHttp: Boolean = false,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (timeoutMs <= 0) add("timeoutMs must be > 0, was $timeoutMs")
            if (maxRequestBytes <= 0) add("maxRequestBytes must be > 0, was $maxRequestBytes")
            if (maxResponseBytes <= 0) add("maxResponseBytes must be > 0, was $maxResponseBytes")
        }
}

/** `flow.trace`：执行记录。 */
@Serializable
public data class FlowTraceSection(
    /** 文本超过该长度存文件，库里只放路径。 */
    @SerialName("inlineMaxChars") val inlineMaxChars: Int = 16384,
    /** 单次运行最多记录的节点明细行数，0 = 不限。 */
    @SerialName("maxDetailRowsPerRun") val maxDetailRowsPerRun: Int = 0,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (inlineMaxChars <= 0) add("inlineMaxChars must be > 0, was $inlineMaxChars")
            if (maxDetailRowsPerRun < 0) add("maxDetailRowsPerRun must be >= 0, was $maxDetailRowsPerRun")
        }
}

/** `flow.author`：AI 生成流程。 */
@Serializable
public data class FlowAuthorSection(
    @SerialName("maxTurns") val maxTurns: Int = 30,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (maxTurns <= 0) add("maxTurns must be > 0, was $maxTurns")
        }
}

/** `flow.contract`：状态契约（docs/design/flows-contracts-evidence §7.2）。 */
@Serializable
public data class FlowContractSection(
    /** 总开关：关闭后不做契约继承与每步必检。 */
    @SerialName("enabled") val enabled: Boolean = true,
    /** 是否继承上一步的结果契约作前置。 */
    @SerialName("inherit") val inherit: Boolean = true,
) : ConfigSection {
    override fun validate(): List<String> = emptyList()
}

/** `flow.author.analysis`：事后证据分析的创作循环预算（flows-contracts-evidence §4.1）。 */
@Serializable
public data class FlowAuthorAnalysisSection(
    /** 分析循环的最大轮数。 */
    @SerialName("maxTurns") val maxTurns: Int = 8,
    /** 使用的模型角色（main / vision / light）。 */
    @SerialName("role") val role: String = "main",
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (maxTurns <= 0) add("maxTurns must be > 0, was $maxTurns")
        }
}

/** `flow.recovery`：操作效果校验与恢复阶梯（flows-recording-recovery.md R2）。 */
@Serializable
@Suppress("MagicNumber") // 配置段的默认值与取值范围本身就是数字，与 assets/flow.json 一一对应
public data class FlowRecoverySection(
    /** 总开关。 */
    @SerialName("enabled") val enabled: Boolean = true,
    /** 恢复阶梯：第 k 次重试执行第 k 个适用动作，不适用的跳过。 */
    @SerialName("ladder")
    val ladder: List<String> =
        listOf(
            "recovery.wait_stable",
            "recovery.alt_click",
            "recovery.scroll_into_view",
            "recovery.dismiss_popup",
            "recovery.back_and_retry",
            "recovery.reopen_app",
            "recovery.restart_app",
        ),
    /** 写屏幕工具节点未配置 onFail 时的隐式升级次数。 */
    @SerialName("implicitMaxAttempts") val implicitMaxAttempts: Int = 3,
    /** 阶梯耗尽且节点无 handoff 时是否交回智能体。 */
    @SerialName("implicitHandoff") val implicitHandoff: Boolean = true,
    /** 是否允许 `recovery.restart_app`。 */
    @SerialName("allowRestartApp") val allowRestartApp: Boolean = true,
    /** AUTO 模式下做屏幕变化弱校验的工具。 */
    @SerialName("verifyTools")
    val verifyTools: List<String> = listOf("tap", "input_text", "open_app"),
    /** 期望效果的轮询等待上限。 */
    @SerialName("verifyTimeoutMs") val verifyTimeoutMs: Long = 1500,
    /** 期望效果的轮询间隔。 */
    @SerialName("verifyPollMs") val verifyPollMs: Long = 200,
    /** `recovery.wait_stable` 的等待时长。 */
    @SerialName("waitStableMs") val waitStableMs: Long = 1500,
    /** 替代点击的比例点（0–1，相对节点 bounds）。 */
    @SerialName("altClickOffsets")
    val altClickOffsets: List<List<Double>> =
        listOf(
            listOf(0.5, 0.5),
            listOf(0.3, 0.5),
            listOf(0.7, 0.5),
            listOf(0.5, 0.3),
            listOf(0.5, 0.7),
        ),
    /** 交回智能体时注入的流程上下文字符上限。 */
    @SerialName("handoffContextMaxChars")
    val handoffContextMaxChars: Int = 4000,
    /** 隐式 handoff 的步数上限（显式 `handoff.maxSteps` 优先）。 */
    @SerialName("handoffMaxSteps")
    val handoffMaxSteps: Int = 30,
    /** 单次 Run 的 handoff 次数上限，超限后不再交回智能体，直接走 `final`。 */
    @SerialName("handoffBudgetPerRun")
    val handoffBudgetPerRun: Int = 5,
    /** 是否允许自动写入流程修订版本（false = 只生成草稿等用户确认）。 */
    @SerialName("autoPatch")
    val autoPatch: Boolean = false,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (implicitMaxAttempts < 0) add("implicitMaxAttempts must be >= 0, was $implicitMaxAttempts")
            if (verifyTimeoutMs !in 200..10000) add("verifyTimeoutMs must be in 200..10000, was $verifyTimeoutMs")
            if (verifyPollMs !in 50..1000) add("verifyPollMs must be in 50..1000, was $verifyPollMs")
            if (waitStableMs < 0) add("waitStableMs must be >= 0, was $waitStableMs")
            if (handoffContextMaxChars < 0) {
                add("handoffContextMaxChars must be >= 0, was $handoffContextMaxChars")
            }
            if (handoffMaxSteps !in 1..200) add("handoffMaxSteps must be in 1..200, was $handoffMaxSteps")
            if (handoffBudgetPerRun !in 1..50) add("handoffBudgetPerRun must be in 1..50, was $handoffBudgetPerRun")
            altClickOffsets.forEach { p ->
                if (p.size != 2 || p.any { it !in 0.0..1.0 }) add("altClickOffsets entries must be [x,y] in 0..1")
            }
        }
}

/** `flow.precondition`：节点前置断言（docs/design/flows-precondition-selfrepair.md P5）。 */
@Serializable
@Suppress("MagicNumber") // 配置段的默认值与取值范围本身就是数字，与 assets/flow.json 一一对应
public data class FlowPreconditionSection(
    /** 总开关；关闭时所有 `pre` 被忽略。 */
    @SerialName("enabled") val enabled: Boolean = true,
    /** `WAIT` 策略的默认等待上限；仍受节点 `timeoutMs` 约束。 */
    @SerialName("waitTimeoutMs") val waitTimeoutMs: Long = 3000,
    /** `WAIT` 策略的轮询间隔。 */
    @SerialName("pollMs") val pollMs: Long = 200,
    /** 是否允许 `pre.cond` 含 `llm` 子条件；为假时运行期忽略该 `pre` 并记 warn。 */
    @SerialName("allowLlm") val allowLlm: Boolean = false,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (waitTimeoutMs !in 0..60000) add("waitTimeoutMs must be in 0..60000, was $waitTimeoutMs")
            if (pollMs !in 50..2000) add("pollMs must be in 50..2000, was $pollMs")
        }
}

/** `flow.directory`：智能体上下文里的流程目录（flows-recording-recovery.md A2）。 */
@Serializable
public data class FlowDirectorySection(
    @SerialName("maxItems") val maxItems: Int = 20,
    @SerialName("maxChars") val maxChars: Int = 1600,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (maxItems <= 0) add("maxItems must be > 0, was $maxItems")
            if (maxChars <= 0) add("maxChars must be > 0, was $maxChars")
        }
}

/** `flow.save`：智能体保存流程时的参数（flows-recording-recovery.md A3）。 */
@Serializable
public data class FlowSaveSection(
    /** 转换流程时，打开应用等页面切换动作后插入的等待区间。 */
    @SerialName("settleMinMs") val settleMinMs: Long = 800,
    @SerialName("settleMaxMs") val settleMaxMs: Long = 1500,
    /** 成功的 Run 至少执行这么多界面操作，才会提示保存为流程（flows-recording-recovery.md A5）。 */
    @SerialName("suggestMinSteps") val suggestMinSteps: Int = 4,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (settleMinMs < 0) add("settleMinMs must be >= 0, was $settleMinMs")
            if (settleMaxMs < settleMinMs) add("settleMaxMs must be >= settleMinMs, was $settleMaxMs")
            if (suggestMinSteps <= 0) add("suggestMinSteps must be > 0, was $suggestMinSteps")
        }
}

/** `flow.recorder`：悬浮球录制（flows-recording-recovery.md V3）。 */
@Serializable
public data class FlowRecorderSection(
    /** 同一节点的同类操作在该间隔内重复出现时丢弃（毫秒）。 */
    @SerialName("minGapMs") val minGapMs: Long = 100,
    /** 单次录制最多保留的操作数。 */
    @SerialName("maxSteps") val maxSteps: Int = 200,
    /** 操作悬浮球、录制面板等自家界面后，该时间内目标应用的窗口切换事件不计入录制（毫秒）。 */
    @SerialName("overlaySuppressMs") val overlaySuppressMs: Long = 800,
    /** 录制面板详情里展示最近几条操作。 */
    @SerialName("recentShown") val recentShown: Int = 8,
) : ConfigSection {
    override fun validate(): List<String> =
        buildList {
            if (minGapMs < 0) add("minGapMs must be >= 0, was $minGapMs")
            if (maxSteps <= 0) add("maxSteps must be > 0, was $maxSteps")
            if (overlaySuppressMs < 0) add("overlaySuppressMs must be >= 0, was $overlaySuppressMs")
            if (recentShown < 1) add("recentShown must be >= 1, was $recentShown")
        }
}

/** `flow` 领域全部配置键的登记（DI 多绑定与测试用）。 */
public object FlowConfig {
    public val RECORDER: ConfigKey<FlowRecorderSection> =
        ConfigKey("flow.recorder", FlowRecorderSection.serializer()) { FlowRecorderSection() }
    public val DIRECTORY: ConfigKey<FlowDirectorySection> =
        ConfigKey("flow.directory", FlowDirectorySection.serializer()) { FlowDirectorySection() }
    public val SAVE: ConfigKey<FlowSaveSection> =
        ConfigKey("flow.save", FlowSaveSection.serializer()) { FlowSaveSection() }

    public val RECOVERY: ConfigKey<FlowRecoverySection> =
        ConfigKey("flow.recovery", FlowRecoverySection.serializer()) { FlowRecoverySection() }

    public val PRECONDITION: ConfigKey<FlowPreconditionSection> =
        ConfigKey("flow.precondition", FlowPreconditionSection.serializer()) { FlowPreconditionSection() }

    public val RUN: ConfigKey<FlowRunSection> =
        ConfigKey("flow.run", FlowRunSection.serializer()) { FlowRunSection() }
    public val LLM: ConfigKey<FlowLlmSection> =
        ConfigKey("flow.llm", FlowLlmSection.serializer()) { FlowLlmSection() }
    public val AGENT: ConfigKey<FlowAgentSection> =
        ConfigKey("flow.agent", FlowAgentSection.serializer()) { FlowAgentSection() }
    public val API: ConfigKey<FlowApiSection> =
        ConfigKey("flow.api", FlowApiSection.serializer()) { FlowApiSection() }
    public val TRACE: ConfigKey<FlowTraceSection> =
        ConfigKey("flow.trace", FlowTraceSection.serializer()) { FlowTraceSection() }
    public val AUTHOR: ConfigKey<FlowAuthorSection> =
        ConfigKey("flow.author", FlowAuthorSection.serializer()) { FlowAuthorSection() }

    public val CONTRACT: ConfigKey<FlowContractSection> =
        ConfigKey("flow.contract", FlowContractSection.serializer()) { FlowContractSection() }

    public val ANALYSIS: ConfigKey<FlowAuthorAnalysisSection> =
        ConfigKey("flow.author.analysis", FlowAuthorAnalysisSection.serializer()) {
            FlowAuthorAnalysisSection()
        }

    public val ALL: List<ConfigKey<*>> =
        listOf(
            RUN,
            LLM,
            AGENT,
            API,
            TRACE,
            AUTHOR,
            ANALYSIS,
            CONTRACT,
            RECOVERY,
            PRECONDITION,
            DIRECTORY,
            SAVE,
            RECORDER,
        )
}
