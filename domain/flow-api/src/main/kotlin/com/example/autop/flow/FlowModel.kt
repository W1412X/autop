/**
 * 流程文档模型（docs/design/flows.md §2）：版本化的 JSON 步骤树。
 *
 * 持久化字段全部固定 `@SerialName`；新字段必须有默认值；读取时 `ignoreUnknownKeys`。
 */
package com.example.autop.flow

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 流程文档常量。 */
public object FlowSpec {
    public const val FORMAT: String = "autop.flow"
    public const val BUNDLE_FORMAT: String = "autop.flow.bundle"
    public const val CURRENT_SCHEMA_VERSION: Int = 1
    public const val BUNDLE_VERSION: Int = 1
}

/** 一个流程定义。 */
@Serializable
public data class FlowDefinition(
    @SerialName("format") val format: String = FlowSpec.FORMAT,
    @SerialName("schemaVersion") val schemaVersion: Int = FlowSpec.CURRENT_SCHEMA_VERSION,
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String = "",
    @SerialName("params") val params: List<FlowParam> = emptyList(),
    @SerialName("settings") val settings: FlowSettings = FlowSettings(),
    @SerialName("steps") val steps: List<Node> = emptyList(),
)

/** 参数类型。 */
@Serializable
public enum class ParamType {
    @SerialName("string")
    STRING,

    @SerialName("number")
    NUMBER,

    @SerialName("boolean")
    BOOLEAN,

    @SerialName("list")
    LIST,

    @SerialName("object")
    OBJECT,

    @SerialName("time")
    TIME,

    @SerialName("any")
    ANY,
}

/** 参数声明。 */
@Serializable
public data class FlowParam(
    @SerialName("name") val name: String,
    @SerialName("type") val type: ParamType = ParamType.STRING,
    @SerialName("required") val required: Boolean = false,
    @SerialName("default") val default: JsonElement? = null,
    @SerialName("description") val description: String = "",
    /** 可选取值集合。 */
    @SerialName("enum") val choices: List<JsonElement>? = null,
    /** 为真时不写入执行记录。 */
    @SerialName("secret") val secret: Boolean = false,
)

/** 执行记录级别。 */
@Serializable
public enum class TraceLevel {
    @SerialName("full")
    FULL,

    @SerialName("failures")
    FAILURES,

    @SerialName("none")
    NONE,
}

/** 流程级设置。 */
@Serializable
public data class FlowSettings(
    @SerialName("observeMode") val observeMode: String = "auto",
    @SerialName("onFail") val onFail: OnFail? = null,
    @SerialName("maxDurationMs") val maxDurationMs: Long? = null,
    @SerialName("pauseAfterConsecutiveFailures") val pauseAfterConsecutiveFailures: Int = 3,
    @SerialName("pacing") val pacing: Pacing? = null,
    @SerialName("budget") val budget: FlowBudget? = null,
    @SerialName("allowedTools") val allowedTools: List<String>? = null,
    @SerialName("trace") val trace: TraceLevel = TraceLevel.FULL,
    @SerialName("memoryLog") val memoryLog: MemoryLogSettings = MemoryLogSettings(),
    /** 动作后效果校验模式（docs/design/flows-recording-recovery.md R1）。 */
    @SerialName("verify") val verify: VerifyMode = VerifyMode.AUTO,
)

/** 动作后效果校验模式。 */
@Serializable
public enum class VerifyMode {
    /** 不校验。 */
    @SerialName("off")
    OFF,

    /** 对 `flow.recovery.verifyTools` 中的工具做屏幕变化弱校验。 */
    @SerialName("auto")
    AUTO,

    /** 所有写屏幕工具都做屏幕变化校验。 */
    @SerialName("strict")
    STRICT,
}

/** 期望类型：动作之后应当出现的效果。 */
@Serializable
public enum class ExpectKind {
    @SerialName("none")
    NONE,

    @SerialName("screenChanged")
    SCREEN_CHANGED,

    @SerialName("text")
    TEXT,

    @SerialName("textGone")
    TEXT_GONE,

    @SerialName("element")
    ELEMENT,

    @SerialName("elementGone")
    ELEMENT_GONE,

    @SerialName("app")
    APP,
}

/** 动作的期望效果。 */
@Serializable
public data class Expect(
    @SerialName("kind") val kind: ExpectKind = ExpectKind.NONE,
    @SerialName("text") val text: String? = null,
    @SerialName("target") val target: Target? = null,
    @SerialName("app") val app: String? = null,
    /** null = 使用 `flow.recovery.verifyTimeoutMs`。 */
    @SerialName("timeoutMs") val timeoutMs: Long? = null,
    /**
     * 任意条件契约（结果契约，docs/design/flows-contracts-evidence §7.1）：
     * 非空时优先于 [kind]；[kind] 保留兼容旧流程（可转换为等价条件）。
     */
    @SerialName("cond") val cond: Condition? = null,
)

/** 节点的锚点：恢复后用于判断是否回到了原来的页面。 */
@Serializable
public data class Anchor(
    @SerialName("app") val app: String? = null,
    @SerialName("text") val text: String? = null,
    @SerialName("target") val target: Target? = null,
)

/** 前置条件不满足时的处理策略。 */
@Serializable
public enum class PrePolicy {
    /** 判定节点失败，进入 onFail 链。 */
    @SerialName("fail")
    FAIL,

    /** 等待条件成立，超过 timeoutMs 仍不成立则判定失败。 */
    @SerialName("wait")
    WAIT,
}

/**
 * 节点前置条件（docs/design/flows-precondition-selfrepair.md P1）。
 *
 * 不成立即判定节点失败，失败进入 `onFail` 链；不成立就跳过节点的需求用 `if` 表达。
 * 条件求值本身出错（非法正则、模板缺变量等）不属于"条件不成立"，按流程定义错误快速失败。
 */
@Serializable
public data class Precondition(
    @SerialName("cond") val cond: Condition,
    @SerialName("policy") val policy: PrePolicy = PrePolicy.FAIL,
    /** 仅 WAIT 策略有效；null 用 `min(节点 timeoutMs, flow.precondition.waitTimeoutMs)`。 */
    @SerialName("timeoutMs") val timeoutMs: Long? = null,
    /** 失败说明；支持模板。 */
    @SerialName("message") val message: String? = null,
    /**
     * 是否允许继承上一步的结果契约作前置（docs/design/flows-contracts-evidence §7.2）：
     * 为真且本节点没写 `pre` 时，生效前置默认取同列表上一个工具节点的结果契约。
     */
    @SerialName("inherit") val inherit: Boolean = true,
)

/** 重试策略：PLAIN 为原地重复执行；ESCALATE 按恢复阶梯逐级升级。 */
@Serializable
public enum class RetryStrategy {
    @SerialName("plain")
    PLAIN,

    @SerialName("escalate")
    ESCALATE,
}

/** 节点间的拟人化间隔。 */
@Serializable
public data class Pacing(
    @SerialName("betweenNodesMs") val betweenNodesMs: DelayRange? = null,
)

/** 流程预算（null = 不限）。 */
@Serializable
public data class FlowBudget(
    @SerialName("maxLlmTokens") val maxLlmTokens: Long? = null,
    @SerialName("maxLlmCalls") val maxLlmCalls: Int? = null,
    @SerialName("maxApiCalls") val maxApiCalls: Int? = null,
)

/** 运行摘要写入记忆（默认关）。 */
@Serializable
public data class MemoryLogSettings(
    @SerialName("enabled") val enabled: Boolean = false,
    @SerialName("keep") val keep: Int = 20,
)

// ---------------------------------------------------------------------------------------------
// 失败处理、确认
// ---------------------------------------------------------------------------------------------

/** 失败处理（docs/design/flows.md §7）：retry → handoff → steps → final。 */
@Serializable
public data class OnFail(
    @SerialName("retry") val retry: RetrySpec? = null,
    @SerialName("handoff") val handoff: Handoff? = null,
    @SerialName("steps") val steps: List<Node> = emptyList(),
    @SerialName("after") val after: AfterRecovery = AfterRecovery.RETRY,
    @SerialName("final") val final: FinalAction = FinalAction.STOP,
)

/** 重试。 */
@Serializable
public data class RetrySpec(
    @SerialName("max") val max: Int,
    @SerialName("delayMs") val delayMs: DelayRange? = null,
    @SerialName("strategy") val strategy: RetryStrategy = RetryStrategy.PLAIN,
    /** 覆盖 `flow.recovery.ladder` 的恢复阶梯（元素为 `recovery.*` 动作 id）。 */
    @SerialName("ladder") val ladder: List<String>? = null,
)

/** 转交智能体。 */
@Serializable
public data class Handoff(
    @SerialName("goal") val goal: String,
    @SerialName("tools") val tools: List<String>? = null,
    @SerialName("maxSteps") val maxSteps: Int? = null,
    @SerialName("maxDurationMs") val maxDurationMs: Long? = null,
)

/** handoff / steps 成功后如何接回。 */
@Serializable
public enum class AfterRecovery {
    @SerialName("retry")
    RETRY,

    @SerialName("continue")
    CONTINUE,
}

/** 所有手段都失败后的动作。 */
@Serializable
public enum class FinalAction {
    @SerialName("stop")
    STOP,

    @SerialName("skip")
    SKIP,

    @SerialName("pause")
    PAUSE,

    @SerialName("succeed")
    SUCCEED,
}

/** 执行前确认。 */
@Serializable
public data class Confirm(
    @SerialName("message") val message: String? = null,
    @SerialName("timeoutMs") val timeoutMs: Long = 60000,
    @SerialName("onTimeout") val onTimeout: ConfirmTimeout = ConfirmTimeout.SKIP,
)

/** 确认超时的处理。 */
@Serializable
public enum class ConfirmTimeout {
    @SerialName("skip")
    SKIP,

    @SerialName("stop")
    STOP,

    @SerialName("proceed")
    PROCEED,
}

// ---------------------------------------------------------------------------------------------
// 目标描述
// ---------------------------------------------------------------------------------------------

/** 目标定位依据。 */
@Serializable
public enum class TargetBy {
    @SerialName("text")
    TEXT,

    @SerialName("icon")
    ICON,

    @SerialName("desc")
    DESC,

    @SerialName("hint")
    HINT,

    @SerialName("tag")
    TAG,
}

/** 文字匹配方式。 */
@Serializable
public enum class MatchMode {
    @SerialName("equals")
    EQUALS,

    @SerialName("segment")
    SEGMENT,

    @SerialName("contains")
    CONTAINS,

    @SerialName("startsWith")
    STARTS_WITH,

    @SerialName("regex")
    REGEX,
}

/** 多个命中时的选择。 */
@Serializable
public enum class Pick {
    @SerialName("unique")
    UNIQUE,

    @SerialName("first")
    FIRST,

    @SerialName("last")
    LAST,

    @SerialName("nth")
    NTH,

    @SerialName("nearest")
    NEAREST,

    @SerialName("all")
    ALL,
}

/** 坐标后备。 */
@Serializable
public data class TargetFallback(
    /** 0–1000 `[x, y]`。 */
    @SerialName("at") val at: List<Int>,
    @SerialName("requireTag") val requireTag: String? = null,
    @SerialName("requireText") val requireText: String? = null,
)

/** 目标描述（执行时解析成坐标）。 */
@Serializable
public data class Target(
    @SerialName("by") val by: TargetBy = TargetBy.TEXT,
    @SerialName("value") val value: String? = null,
    @SerialName("match") val match: MatchMode = MatchMode.SEGMENT,
    @SerialName("tag") val tag: String? = null,
    @SerialName("pick") val pick: Pick = Pick.UNIQUE,
    @SerialName("nth") val nth: Int? = null,
    @SerialName("within") val within: Target? = null,
    /** 0–1000 `[x1, y1, x2, y2]`。 */
    @SerialName("region") val region: List<Int>? = null,
    /** null = 按用途（点击类默认 true）。 */
    @SerialName("actionable") val actionable: Boolean? = null,
    @SerialName("fallback") val fallback: TargetFallback? = null,
    /** 覆盖节点的观测模式。 */
    @SerialName("mode") val mode: String? = null,
)

// ---------------------------------------------------------------------------------------------
// 条件
// ---------------------------------------------------------------------------------------------

/** 条件（判别字段 `kind`）。 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
public sealed interface Condition {
    /** 表达式。 */
    @Serializable
    @SerialName("expr")
    public data class Expr(
        @SerialName("expr") val expr: String,
    ) : Condition

    /** 页面文字。 */
    @Serializable
    @SerialName("screenText")
    public data class ScreenText(
        @SerialName("op") val op: TextOp = TextOp.CONTAINS,
        @SerialName("value") val value: String,
        @SerialName("mode") val mode: String? = null,
    ) : Condition

    /** 目标元素状态。 */
    @Serializable
    @SerialName("element")
    public data class Element(
        @SerialName("op") val op: ElementOp = ElementOp.EXISTS,
        @SerialName("target") val target: Target,
    ) : Condition

    /** 前台应用。 */
    @Serializable
    @SerialName("app")
    public data class App(
        @SerialName("value") val value: String,
        @SerialName("page") val page: String? = null,
    ) : Condition

    /** 屏幕是否变化。 */
    @Serializable
    @SerialName("screenChanged")
    public data class ScreenChanged(
        /** `lastAction` 或节点 id。 */
        @SerialName("since") val since: String = "lastAction",
    ) : Condition

    /** 输入法。 */
    @Serializable
    @SerialName("ime")
    public data class Ime(
        @SerialName("shown") val shown: Boolean = true,
    ) : Condition

    /** 设备状态。 */
    @Serializable
    @SerialName("device")
    public data class Device(
        @SerialName("metric") val metric: DeviceMetric,
        @SerialName("op") val op: CompareOp = CompareOp.EQ,
        @SerialName("value") val value: JsonElement,
    ) : Condition

    /** 时间段 / 星期。 */
    @Serializable
    @SerialName("time")
    public data class TimeWindow(
        /** `["HH:mm", "HH:mm"]`，起止可跨午夜。 */
        @SerialName("between") val between: List<String>? = null,
        /** 1=周一 … 7=周日。 */
        @SerialName("weekdays") val weekdays: List<Int>? = null,
    ) : Condition

    /** 指定节点最近一次是否成功。 */
    @Serializable
    @SerialName("lastOk")
    public data class LastOk(
        @SerialName("node") val node: String,
    ) : Condition

    /** LLM 判断（结果为布尔）。 */
    @Serializable
    @SerialName("llm")
    public data class Llm(
        @SerialName("prompt") val prompt: String,
        @SerialName("screen") val screen: Boolean = false,
        @SerialName("images") val images: List<String> = emptyList(),
    ) : Condition

    /** 全部为真。 */
    @Serializable
    @SerialName("all")
    public data class All(
        @SerialName("of") val of: List<Condition>,
    ) : Condition

    /** 任一为真。 */
    @Serializable
    @SerialName("any")
    public data class Any(
        @SerialName("of") val of: List<Condition>,
    ) : Condition

    /** 取反。 */
    @Serializable
    @SerialName("not")
    public data class Not(
        @SerialName("of") val of: Condition,
    ) : Condition
}

/** 文字比较。 */
@Serializable
public enum class TextOp {
    @SerialName("contains")
    CONTAINS,

    @SerialName("notContains")
    NOT_CONTAINS,

    @SerialName("equals")
    EQUALS,

    @SerialName("regex")
    REGEX,
}

/** 元素状态。 */
@Serializable
public enum class ElementOp {
    @SerialName("exists")
    EXISTS,

    @SerialName("absent")
    ABSENT,

    @SerialName("checked")
    CHECKED,

    @SerialName("unchecked")
    UNCHECKED,

    @SerialName("enabled")
    ENABLED,

    @SerialName("disabled")
    DISABLED,

    @SerialName("focused")
    FOCUSED,
}

/** 设备指标。 */
@Serializable
public enum class DeviceMetric {
    @SerialName("battery")
    BATTERY,

    @SerialName("charging")
    CHARGING,

    @SerialName("network")
    NETWORK,

    @SerialName("screenOn")
    SCREEN_ON,

    @SerialName("locked")
    LOCKED,
}

/** 比较运算。 */
@Serializable
public enum class CompareOp {
    @SerialName("==")
    EQ,

    @SerialName("!=")
    NE,

    @SerialName("<")
    LT,

    @SerialName("<=")
    LE,

    @SerialName(">")
    GT,

    @SerialName(">=")
    GE,
}

// ---------------------------------------------------------------------------------------------
// 节点
// ---------------------------------------------------------------------------------------------

/** 节点（判别字段 `type`）。公共字段见 docs/design/flows.md §2.5。 */
@Serializable
public sealed interface Node {
    public val id: String
    public val name: String?
    public val comment: String?
    public val enabled: Boolean
    public val onFail: OnFail?
    public val confirm: Confirm?
    public val timeoutMs: Long?
    public val trace: TraceLevel?

    /** 进入本节点前的前置断言；null = 不检查。 */
    public val pre: Precondition?
}

/** 调用工具。 */
@Serializable
@SerialName("tool")
public data class ToolNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
    @SerialName("tool") val tool: String,
    @SerialName("args") val args: JsonObject = JsonObject(emptyMap()),
    @SerialName("target") val target: Target? = null,
    @SerialName("target2") val target2: Target? = null,
    @SerialName("out") val out: String? = null,
    /** 动作后的期望效果（为空时按 settings.verify 决定是否做弱校验）。 */
    @SerialName("expect") val expect: Expect? = null,
    /** 恢复后判断是否回到原页面的锚点。 */
    @SerialName("anchor") val anchor: Anchor? = null,
) : Node

/** 观测屏幕。 */
@Serializable
@SerialName("observe")
public data class ObserveNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
    @SerialName("mode") val mode: String? = null,
    @SerialName("region") val region: List<Int>? = null,
    @SerialName("out") val out: String? = null,
) : Node

/** 读取来源。 */
@Serializable
public enum class ReadFrom {
    @SerialName("target")
    TARGET,

    @SerialName("list")
    LIST,

    @SerialName("page")
    PAGE,

    @SerialName("region")
    REGION,

    @SerialName("screenshot")
    SCREENSHOT,
}

/** 读取的属性。 */
@Serializable
public enum class ReadAttr {
    @SerialName("text")
    TEXT,

    @SerialName("desc")
    DESC,

    @SerialName("hint")
    HINT,

    @SerialName("state")
    STATE,

    @SerialName("checked")
    CHECKED,

    @SerialName("exists")
    EXISTS,

    @SerialName("bounds")
    BOUNDS,

    @SerialName("count")
    COUNT,
}

/** 读取屏幕信息。 */
@Serializable
@SerialName("read")
public data class ReadNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
    @SerialName("from") val from: ReadFrom,
    @SerialName("target") val target: Target? = null,
    @SerialName("attr") val attr: ReadAttr = ReadAttr.TEXT,
    @SerialName("region") val region: List<Int>? = null,
    @SerialName("out") val out: String? = null,
) : Node

/** 等待类型。 */
@Serializable
public enum class WaitFor {
    @SerialName("time")
    TIME,

    @SerialName("text")
    TEXT,

    @SerialName("gone")
    GONE,

    @SerialName("element")
    ELEMENT,

    @SerialName("elementGone")
    ELEMENT_GONE,

    @SerialName("change")
    CHANGE,

    @SerialName("stable")
    STABLE,

    @SerialName("app")
    APP,

    @SerialName("notification")
    NOTIFICATION,

    @SerialName("cond")
    COND,

    @SerialName("until")
    UNTIL,
}

/** 超时后的处理。 */
@Serializable
public enum class WaitTimeoutAction {
    @SerialName("fail")
    FAIL,

    @SerialName("continue")
    CONTINUE,
}

/** 等待。 */
@Serializable
@SerialName("wait")
public data class WaitNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
    @SerialName("for") val waitFor: WaitFor,
    /** `time`：毫秒或 `{min,max}`。 */
    @SerialName("ms") val ms: DelayRange? = null,
    /** `text` / `gone` / `app` / `until` 的值，支持模板。 */
    @SerialName("value") val value: String? = null,
    @SerialName("page") val page: String? = null,
    @SerialName("target") val target: Target? = null,
    @SerialName("since") val since: String? = null,
    @SerialName("package") val packageName: String? = null,
    @SerialName("contains") val contains: String? = null,
    @SerialName("sender") val sender: String? = null,
    @SerialName("cond") val cond: Condition? = null,
    @SerialName("onTimeout") val onTimeout: WaitTimeoutAction = WaitTimeoutAction.FAIL,
    @SerialName("out") val out: String? = null,
) : Node

/** LLM 输出类型。 */
@Serializable
public enum class LlmOutputType {
    @SerialName("text")
    TEXT,

    @SerialName("json")
    JSON,

    @SerialName("label")
    LABEL,

    @SerialName("bool")
    BOOL,

    @SerialName("number")
    NUMBER,
}

/** LLM 输出规格。 */
@Serializable
public data class LlmOutput(
    @SerialName("type") val type: LlmOutputType = LlmOutputType.TEXT,
    @SerialName("schema") val schema: JsonObject? = null,
    @SerialName("labels") val labels: List<String>? = null,
)

/** LLM 输出校验器（判别字段 `kind`）。 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
public sealed interface Validator {
    public val message: String?

    /** 最大长度。 */
    @Serializable
    @SerialName("maxLen")
    public data class MaxLen(
        @SerialName("n") val n: Int,
        @SerialName("message") override val message: String? = null,
    ) : Validator

    /** 最小长度。 */
    @Serializable
    @SerialName("minLen")
    public data class MinLen(
        @SerialName("n") val n: Int,
        @SerialName("message") override val message: String? = null,
    ) : Validator

    /** 正则。 */
    @Serializable
    @SerialName("regex")
    public data class Regex(
        @SerialName("pattern") val pattern: String,
        @SerialName("mustMatch") val mustMatch: Boolean = true,
        @SerialName("message") override val message: String? = null,
    ) : Validator

    /** 不得包含。 */
    @Serializable
    @SerialName("notContains")
    public data class NotContains(
        @SerialName("values") val values: List<String>,
        @SerialName("message") override val message: String? = null,
    ) : Validator

    /** 取值集合。 */
    @Serializable
    @SerialName("oneOf")
    public data class OneOf(
        @SerialName("values") val values: List<JsonElement>,
        @SerialName("message") override val message: String? = null,
    ) : Validator

    /** 数值范围。 */
    @Serializable
    @SerialName("range")
    public data class Range(
        @SerialName("min") val min: Double? = null,
        @SerialName("max") val max: Double? = null,
        @SerialName("message") override val message: String? = null,
    ) : Validator

    /** 表达式（`out` 指向本次输出）。 */
    @Serializable
    @SerialName("expr")
    public data class Expr(
        @SerialName("expr") val expr: String,
        @SerialName("message") override val message: String? = null,
    ) : Validator
}

/** LLM 校验失败的重试。 */
@Serializable
public data class LlmRetry(
    @SerialName("max") val max: Int? = null,
    @SerialName("feedback") val feedback: Boolean = true,
)

/** LLM 调用限制。 */
@Serializable
public data class LlmLimits(
    @SerialName("maxOutputTokens") val maxOutputTokens: Int? = null,
    @SerialName("maxInputTokens") val maxInputTokens: Int? = null,
)

/** 校验最终失败时的默认值。 */
@Serializable
public data class OnInvalid(
    /** 默认值表达式。 */
    @SerialName("use") val use: String,
)

/** LLM 调用。 */
@Serializable
@SerialName("llm")
public data class LlmNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
    @SerialName("system") val system: String? = null,
    @SerialName("prompt") val prompt: String,
    @SerialName("screen") val screen: Boolean = false,
    @SerialName("images") val images: List<String> = emptyList(),
    @SerialName("output") val output: LlmOutput = LlmOutput(),
    @SerialName("validators") val validators: List<Validator> = emptyList(),
    @SerialName("retry") val retry: LlmRetry = LlmRetry(),
    @SerialName("limits") val limits: LlmLimits = LlmLimits(),
    @SerialName("temperature") val temperature: Double? = null,
    @SerialName("onInvalid") val onInvalid: OnInvalid? = null,
    @SerialName("out") val out: String? = null,
) : Node

/** 条件分支。 */
@Serializable
@SerialName("if")
public data class IfNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
    @SerialName("cond") val cond: Condition,
    @SerialName("then") val thenSteps: List<Node> = emptyList(),
    @SerialName("else") val elseSteps: List<Node> = emptyList(),
) : Node

/** switch 的一个 case。 */
@Serializable
public data class SwitchCase(
    @SerialName("values") val values: List<JsonElement>,
    @SerialName("steps") val steps: List<Node> = emptyList(),
)

/** 多路分支。 */
@Serializable
@SerialName("switch")
public data class SwitchNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
    @SerialName("on") val on: String,
    @SerialName("cases") val cases: List<SwitchCase> = emptyList(),
    @SerialName("default") val default: List<Node> = emptyList(),
) : Node

/** 循环模式。 */
@Serializable
public enum class LoopMode {
    @SerialName("count")
    COUNT,

    @SerialName("while")
    WHILE,

    @SerialName("until")
    UNTIL,

    @SerialName("forever")
    FOREVER,

    @SerialName("each")
    EACH,
}

/** 循环。 */
@Serializable
@SerialName("loop")
public data class LoopNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
    @SerialName("mode") val mode: LoopMode,
    /** `count` 次数表达式。 */
    @SerialName("count") val count: String? = null,
    /** `while` / `until` 条件。 */
    @SerialName("cond") val cond: Condition? = null,
    /** `each` 列表表达式。 */
    @SerialName("items") val items: String? = null,
    @SerialName("itemVar") val itemVar: String = "item",
    @SerialName("indexVar") val indexVar: String = "i",
    @SerialName("maxIterations") val maxIterations: Long? = null,
    @SerialName("maxDurationMs") val maxDurationMs: Long? = null,
    @SerialName("delayMs") val delayMs: DelayRange? = null,
    @SerialName("continueOnFail") val continueOnFail: Boolean = false,
    @SerialName("resumeKey") val resumeKey: String? = null,
    @SerialName("steps") val steps: List<Node> = emptyList(),
) : Node

/** 跳出最近的循环。 */
@Serializable
@SerialName("break")
public data class BreakNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
) : Node

/** 进入最近循环的下一轮。 */
@Serializable
@SerialName("continue")
public data class ContinueNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
) : Node

/** 调用子流程。 */
@Serializable
@SerialName("call")
public data class CallNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
    /** 流程名或 ID。 */
    @SerialName("flow") val flow: String,
    @SerialName("args") val args: JsonObject = JsonObject(emptyMap()),
    @SerialName("out") val out: String? = null,
) : Node

/** 交给智能体。 */
@Serializable
@SerialName("agent")
public data class AgentNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
    @SerialName("goal") val goal: String,
    @SerialName("tools") val tools: List<String>? = null,
    @SerialName("maxSteps") val maxSteps: Int? = null,
    @SerialName("maxDurationMs") val maxDurationMs: Long? = null,
    @SerialName("out") val out: String? = null,
) : Node

/** HTTP 方法。 */
@Serializable
public enum class HttpMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
}

/** 请求体类型。 */
@Serializable
public enum class BodyType {
    @SerialName("none")
    NONE,

    @SerialName("json")
    JSON,

    @SerialName("form")
    FORM,

    @SerialName("text")
    TEXT,
}

/** 调用已登记接口。 */
@Serializable
@SerialName("api")
public data class ApiNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
    @SerialName("endpoint") val endpoint: String,
    @SerialName("method") val method: HttpMethod = HttpMethod.GET,
    @SerialName("path") val path: String = "",
    @SerialName("query") val query: JsonObject = JsonObject(emptyMap()),
    @SerialName("headers") val headers: JsonObject = JsonObject(emptyMap()),
    @SerialName("bodyType") val bodyType: BodyType = BodyType.NONE,
    @SerialName("body") val body: JsonElement? = null,
    /** 状态码白名单：单个码或区间 `[200, 299]` 的列表；空 = 2xx。 */
    @SerialName("expectStatus") val expectStatus: List<JsonElement> = emptyList(),
    @SerialName("out") val out: String? = null,
) : Node

/** 变量赋值（按书写顺序依次求值）。 */
@Serializable
@SerialName("set")
public data class SetNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
    @SerialName("vars") val vars: Map<String, String>,
) : Node

/** 状态操作。 */
@Serializable
public enum class StateOp {
    @SerialName("get")
    GET,

    @SerialName("set")
    SET,

    @SerialName("inc")
    INC,

    @SerialName("delete")
    DELETE,
}

/** 状态作用域。 */
@Serializable
public enum class StateScope {
    @SerialName("task")
    TASK,

    @SerialName("flow")
    FLOW,
}

/** 跨运行状态。 */
@Serializable
@SerialName("state")
public data class StateNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
    @SerialName("op") val op: StateOp,
    @SerialName("key") val key: String,
    /** `set` 的值表达式；`inc` 的增量表达式（默认 1）。 */
    @SerialName("value") val value: String? = null,
    @SerialName("scope") val scope: StateScope = StateScope.TASK,
    @SerialName("ttlMs") val ttlMs: Long? = null,
    @SerialName("resetDaily") val resetDaily: Boolean = false,
    /** `get` 找不到时的默认值表达式。 */
    @SerialName("default") val default: String? = null,
    @SerialName("out") val out: String? = null,
) : Node

/** 自定义日志。 */
@Serializable
@SerialName("log")
public data class LogNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
    @SerialName("message") val message: String,
    @SerialName("level") val level: String = "info",
) : Node

/** 结束状态。 */
@Serializable
public enum class EndStatus {
    @SerialName("success")
    SUCCESS,

    @SerialName("failure")
    FAILURE,
}

/** 结束（顶层结束运行；子流程内结束子流程并返回 outputs）。 */
@Serializable
@SerialName("end")
public data class EndNode(
    @SerialName("id") override val id: String,
    @SerialName("name") override val name: String? = null,
    @SerialName("comment") override val comment: String? = null,
    @SerialName("enabled") override val enabled: Boolean = true,
    @SerialName("onFail") override val onFail: OnFail? = null,
    @SerialName("confirm") override val confirm: Confirm? = null,
    @SerialName("timeoutMs") override val timeoutMs: Long? = null,
    @SerialName("trace") override val trace: TraceLevel? = null,
    @SerialName("pre") override val pre: Precondition? = null,
    @SerialName("status") val status: EndStatus = EndStatus.SUCCESS,
    @SerialName("message") val message: String? = null,
    /** 输出名 → 表达式。 */
    @SerialName("outputs") val outputs: Map<String, String> = emptyMap(),
) : Node
