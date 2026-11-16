/**
 * Tool SPI（docs/tech/04 §2.3）：工具的声明、执行与运行期上下文。
 *
 * 工具描述文字来自 prompt 模板（见 [ToolSpec.descriptionPromptId]），参数结构在代码中
 * 由 [toolSpec] DSL 定义，参数说明取模板 `meta["param.<名>"]`。
 */
package com.example.autop.agent

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Capability
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.RunMode
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.BetaFeature
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.llm.Block
import com.example.autop.policy.PolicySubject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 工具的访问类别：决定 ToolRouter 的加锁与策略检查。 */
public enum class ToolAccess {
    READ_SCREEN,
    WRITE_SCREEN,
    READ_MEMORY,
    WRITE_MEMORY,
    READ_SYSTEM,
    WRITE_SYSTEM,
    CONTROL,
}

/** 工具声明：[parameters] 为参数 JSON Schema（由 [toolSpec] 生成）。 */
public data class ToolSpec(
    val name: String,
    val parameters: JsonObject,
    val descriptionPromptId: String,
    /** 描述模板的渲染变量（如 `tool.observe` 的 `modeList`），missing 会渲染失败。 */
    val descriptionVars: Map<String, String> = emptyMap(),
)

/** 一个工具。 */
public interface Tool {
    /** 工具 id（如 `tool.tap`），与 prompt 模板 id 命名空间一致。 */
    public val id: String

    public val spec: ToolSpec

    /**
     * 运行期 spec（docs/design/observation-modes-coordinate-actions §2.3）：
     * 参数枚举与说明可按 Run 上下文动态生成（如 `observe.mode` 的生效模式）；
     * 默认与 [spec] 相同。
     */
    public fun specFor(ctx: ToolContext): ToolSpec = spec

    public val access: ToolAccess

    /**
     * 是否会读写手机屏幕（ADR-0035）：为真时执行前必须取得屏幕（按需占用），「只聊天」下不可见。
     * 默认由访问类别推导；会在工具内部操作屏幕、但类别不是屏幕类（如 `run_flow`）的工具需覆盖为真。
     */
    public val touchesScreen: Boolean
        get() = access == ToolAccess.READ_SCREEN || access == ToolAccess.WRITE_SCREEN

    /**
     * 工具需要的最低运行模式（ADR-0040）：Run 当前档位低于它时工具不可见。
     * 默认由 [touchesScreen] 推导：屏幕工具需要标准模式，其余仅聊天即可可见。
     */
    public val minMode: RunMode
        get() = if (touchesScreen) RunMode.STANDARD else RunMode.CHAT_ONLY

    /**
     * 本次调用是否需要屏幕（ADR-0041）：执行前据此取得屏幕租约。默认同 [touchesScreen]；
     * 参数决定是否占屏的工具（如 `run_shell` 的 `screen`）覆盖此方法，只读 [args]，不做推断。
     */
    public fun requiresScreen(args: JsonObject): Boolean = touchesScreen

    /**
     * 本次调用能否与同批相邻的可并行调用并发执行（ADR-0041）。默认：只读的记忆类与系统类访问。
     * 参数解析失败时路由层按 false 处理。
     */
    public fun parallelizable(args: JsonObject): Boolean =
        access == ToolAccess.READ_MEMORY || access == ToolAccess.READ_SYSTEM

    /** 本次调用的超时（毫秒，ADR-0041）；null 表示使用配置的每工具超时。 */
    public fun timeoutMs(args: JsonObject): Long? = null

    /**
     * 运行期可用性（shell-exec 设计 §2.2）：为假时工具静默不出现在本轮工具列表，也不记入隐藏清单。
     * 每次组装工具列表时判断。默认可用。
     */
    public fun isAvailable(config: ConfigSnapshot): Boolean = true

    /**
     * 执行前的检查（ADR-0041，shell-exec 设计 §4.3）：在占屏与取槽之前执行。
     * 返回非 null 时直接作为本次调用的结果，工具本体不再执行；返回 null 表示继续。
     * 确认类工具在此询问用户。
     */
    public suspend fun preflight(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput? = null

    /** 设备能力要求（如截图需要 API 30+），不满足时不出现在可用工具列表。 */
    public val requiredCapabilities: Set<Capability>
        get() = emptySet()

    /**
     * 所属的 Beta 功能（docs/adr/0030-beta-feature-switch.md）；null = 始终可见。
     * 对应功能关闭时，工具不出现在可用工具列表，也不写入运行状态消息。
     */
    public val betaFeature: BetaFeature?
        get() = null

    public suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput
}

/** 屏幕写动作的准备结果：策略主体 + 工具私有数据。 */
public data class PreparedAction(
    val subject: PolicySubject,
    val payload: Any,
)

/** WRITE_SCREEN 工具必须实现：先准备（解析目标、生成策略主体），再执行。 */
public interface ScreenActionTool : Tool {
    /** 解析目标并生成 [PreparedAction]；ToolRouter 在两步之间做策略检查。 */
    public suspend fun prepare(
        args: JsonObject,
        ctx: ToolContext,
    ): Outcome<PreparedAction>

    public suspend fun perform(
        action: PreparedAction,
        ctx: ToolContext,
    ): ToolOutput

    override suspend fun invoke(
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput = error("ScreenActionTool 由 ToolRouter 分两步调用")
}

/** 工具输出。 */
public data class ToolOutput(
    val ok: Boolean,
    /** Text / Image 块。 */
    val content: List<Block>,
    /** 结构化结果，给 UI 与工具链。 */
    val data: JsonElement? = null,
    val error: AppError? = null,
    val observation: ObservationRef? = null,
    val control: ToolControl? = null,
    /** R1/B1：content 是差量观测时的完整版本，写入 ToolResultEvent.fullBlocks（tool_optimize 后不再填写）。 */
    val fullContent: List<Block>? = null,
    /** R1/A6：WRITE_SCREEN 工具成功时填写的可回放记录（docs/tech/04 §2.7）。 */
    val replay: ReplayableAction? = null,
    /**
     * 执行记录：工具实际执行了哪些步骤、经由哪个通道（docs/design/tool_optimize/01 §2）。
     * 成功与失败都由 ToolExecutor 原样附在结果末尾给模型看；工具不要再把这些行拼进 content。
     */
    val trace: List<String> = emptyList(),
    /**
     * 失败时是否可能已经产生副作用（ADR-0041）。默认真：保持“写类失败后跳过同批次后续写操作”的保守行为。
     * 只有确定未派发（参数非法、被拒绝、资源忙、不可用）的失败才应设为假。成功时无意义。
     */
    val effectMayHaveHappened: Boolean = true,
)

/** 工具对 Run 生命周期的控制指令。 */
public sealed interface ToolControl {
    /** 显式交付结束。 */
    public data class Finish(
        val status: RunOutcome,
        val summary: String,
        val outputs: JsonObject?,
    ) : ToolControl

    /** 暂停 Run。 */
    public data class Pause(
        val reason: PauseReason,
        val detail: String?,
    ) : ToolControl
}

/** 工具执行上下文。 */
public interface ToolContext {
    public val runId: RunId

    public val threadId: ThreadId

    public val config: ConfigSnapshot

    /** 本次 Run 内工具之间共享的状态（如设备领域的短 ID 注册表）。 */
    public val runScope: RunScopedValues

    /** N208：工具自测采集视觉链路调试图（检测渲染图＋逐框识别 I/O）；正常 Run 不产出。 */
    public val debugImages: Boolean
        get() = false

    /**
     * 在本 Run 的锁、超时、策略与审计约束下调用其他工具的入口（docs/design/flows.md §8.2）；
     * 智能体 Run 与流程 Run 提供，其余上下文为 null。
     */
    public val tools: ToolInvoker?
        get() = null

    /**
     * 为真时（流程调用）动作工具收尾只等界面稳定，不生成回放；为假时（智能体调用）按
     * `device.action.delayMs` 固定等待。两种情况都不自动观测（docs/design/flows.md §8.2）。
     */
    public val quietActions: Boolean
        get() = false

    /** 本次调用来自智能体还是流程；`memory_write` 据此标记记忆来源。 */
    public val origin: ToolOrigin
        get() = ToolOrigin.AGENT

    /** 调用方标签（如 `flow:<流程名>`）；`memory_write` 据此补默认 reason。 */
    public val originTag: String?
        get() = null

    /** 用于 saveBlob、history_recall。 */
    public val threadStore: ThreadStore

    /** 向用户提问（ask_user 工具）；超时返回错误。 */
    public suspend fun askUser(
        question: String,
        options: List<String>,
        timeoutMs: Long,
    ): Outcome<String>

    /** 工具执行中的进度文本，给 UI。 */
    public fun emitProgress(text: String)
}

/** 工具调用的发起方。 */
public enum class ToolOrigin {
    AGENT,
    FLOW,
}

/**
 * 工具调用器（docs/design/flows.md §8.2）：与智能体相同的 schema 校验、读写锁、超时、
 * 策略两步检查与审计。调用器本身不做名单限制，由调用方（流程校验器等）决定可用工具。
 */
public interface ToolInvoker {
    /** 按模型可见名称（如 `tap`）执行一次调用；工具不存在或未启用返回 `agent.unknown_tool` 错误输出。 */
    public suspend fun invoke(
        toolName: String,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput
}

/** 为一次 Run 构造 [ToolInvoker]：按配置启用清单、[toolFilter] 与设备能力过滤工具，锁独立。 */
public interface ToolInvokerFactory {
    public fun create(
        request: RunRequest,
        toolFilter: Set<String>?,
    ): ToolInvoker
}

/** Run 内共享值容器：键稳定的惰性单例。 */
public interface RunScopedValues {
    public fun <T : Any> getOrPut(
        key: String,
        create: () -> T,
    ): T
}
