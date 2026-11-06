/**
 * 解释器端口（docs/design/flows.md §8.1）。flow-core 只定义，flow-impl 实现；
 * 测试用假实现替代。端口失败以 [com.example.autop.flow.core.expr.FlowException] 报告（带错误码）。
 */
@file:Suppress("LongParameterList") // 解释器端口集合：一个端口一个参数

package com.example.autop.flow.core

import com.example.autop.agent.PauseReason
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolControl
import com.example.autop.device.DeviceState
import com.example.autop.device.NotificationInfo
import com.example.autop.device.ScreenModel
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.NodeStatus
import com.example.autop.flow.PauseResolution
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId

/** 工具调用结果。 */
public data class ToolCallResult(
    val ok: Boolean,
    val text: String,
    val data: JsonElement? = null,
    val errorCode: String? = null,
    val errorHint: String? = null,
    /** 工具要求暂停（策略拦截等）。 */
    val control: ToolControl? = null,
)

/** 工具端口：经 `ToolInvoker` 执行。 */
public interface ToolPort {
    public suspend fun call(
        tool: String,
        args: JsonObject,
        nodeId: String,
    ): ToolCallResult

    /** 工具访问类别；未知工具返回 null。用于屏幕脏标记与 needsScreen 推断。 */
    public fun accessOf(tool: String): ToolAccess?
}

/** 一次观测：解析后的屏幕与原始文本（原始文本用于 LLM 与记录）。 */
public data class Observed(
    val screen: ScreenModel,
    val rawText: String,
)

/** 观测端口。 */
public interface ObservePort {
    /** 完整观测；[mode] 为 null 用流程设置。模式不可用抛 `device.mode_unavailable`。 */
    public suspend fun observe(mode: String?): Observed

    /** 截图并返回图片引用（`img://runId/seq`），[region] 为 0–1000 `[x1,y1,x2,y2]`。 */
    public suspend fun screenshot(region: List<Int>?): String
}

/** LLM 调用请求。 */
public data class LlmCall(
    val nodeId: String,
    val role: String?,
    val system: String?,
    val prompt: String,
    /** 附带的图片引用。 */
    val images: List<String>,
    /** 附带当前屏幕 JSON（已作为不可信数据由端口包装）。 */
    val screenText: String?,
    /** 为真时端口应附带当前截图（视觉模型可用时）。 */
    val attachScreenshot: Boolean,
    val jsonSchema: JsonObject?,
    val maxOutputTokens: Int?,
    val temperature: Double?,
    /** 上一次输出未通过校验时的说明；端口用模板 low.llm.retry_feedback 回灌给模型。 */
    val feedback: String? = null,
    val previousOutput: String? = null,
)

/** LLM 回复。 */
public data class LlmReply(
    val text: String,
    val promptTokens: Long,
    val completionTokens: Long,
)

/** LLM 端口。 */
public interface LlmPort {
    public suspend fun complete(call: LlmCall): LlmReply
}

/** API 请求（已渲染模板）。 */
public data class ApiCall(
    val endpoint: String,
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val bodyType: String,
    val body: JsonElement?,
    val timeoutMs: Long?,
)

/** API 响应。 */
public data class ApiReply(
    val status: Int,
    val headers: Map<String, String>,
    val json: JsonElement?,
    val text: String,
)

/** API 端口。 */
public interface ApiPort {
    public suspend fun call(call: ApiCall): ApiReply
}

/** 智能体子 Run 结果。 */
public data class AgentResult(
    val ok: Boolean,
    val summary: String,
    val outputs: JsonObject?,
    val errorCode: String?,
)

/**
 * 交回智能体时携带的流程上下文（docs/design/flows-precondition-selfrepair.md C1）。
 *
 * 让接手的智能体直接知道"这是哪个流程的哪一步、期望达到什么、现在在哪、前面做了什么"，
 * 而不是从屏幕重新摸索。含屏幕原文的字段必须已包好不可信信封。
 */
public data class AgentContext(
    /** 流程 id 与名称。 */
    val flowId: String,
    val flowName: String,
    /** 失败节点。 */
    val nodeId: String,
    val nodeName: String?,
    /** 节点类型名（tool / llm / agent …）。 */
    val nodeType: String,
    /** 该节点的目标描述，可空。 */
    val targetHint: String?,
    /** 该节点的期望效果，可空。 */
    val expectation: String?,
    /** 该节点的锚点，可空。 */
    val anchorHint: String?,
    /** 前置条件不成立时的明细；其他失败阶段为空。 */
    val preconditionDetail: String?,
    /** 失败错误码与说明。 */
    val failureCode: String?,
    val failureMessage: String?,
    /** 已执行节点摘要，每项 "节点名(类型): ok/failed"；不含参数内容。 */
    val executedSteps: List<String>,
    /** 当前屏幕摘要（前台包名 + 可见文本片段），已包不可信信封。 */
    val screenSummary: String?,
    /** 已尝试过的恢复动作 id。 */
    val triedRecoveries: List<String>,
)

/** 智能体端口。 */
public interface AgentPort {
    public suspend fun run(
        nodeId: String,
        goal: String,
        tools: List<String>?,
        maxSteps: Int?,
        maxDurationMs: Long?,
        role: String?,
        context: AgentContext? = null,
    ): AgentResult
}

/** 状态端口。 */
public interface StatePort {
    public suspend fun get(
        scope: StateAddress,
        key: String,
    ): JsonElement?

    public suspend fun set(
        scope: StateAddress,
        key: String,
        value: JsonElement,
        ttlMs: Long?,
    )

    public suspend fun delete(
        scope: StateAddress,
        key: String,
    )
}

/** 状态作用域的解析结果。 */
public data class StateAddress(
    val scope: String,
)

/** 通知等待。 */
public interface NotificationPort {
    /** 等到匹配的新通知，超时返回 null。 */
    public suspend fun await(
        packageName: String?,
        contains: String?,
        sender: String?,
        sinceMs: Long,
        timeoutMs: Long,
    ): NotificationInfo?
}

/** 设备状态。 */
public interface DevicePort {
    public fun state(): DeviceState
}

/** 用户交互。 */
public interface UserPort {
    /** 执行前确认；[message] 为 null 时用默认文案。超时返回 null。 */
    public suspend fun confirm(
        nodeId: String,
        nodeName: String?,
        message: String?,
        timeoutMs: Long,
    ): Boolean?
}

/** 子流程解析。 */
public interface FlowResolver {
    public suspend fun resolve(nameOrId: String): FlowDefinition?
}

/** 一行节点记录的开始信息。 */
public data class NodeTraceStart(
    val flowId: String,
    val nodeId: String,
    val nodeType: String,
    val nodeName: String?,
    val path: String,
)

/** 节点记录的结束信息。 */
public data class NodeTraceEnd(
    val input: JsonElement?,
    val status: NodeStatus,
    val errorCode: String?,
    val message: String?,
    val output: JsonElement?,
    val attempts: Int,
    val llmTokens: Long,
    val attachments: List<String> = emptyList(),
)

/** 执行记录。 */
public interface TracePort {
    /** 返回行句柄；记录被关闭或超出行数上限时返回 -1。 */
    public suspend fun nodeStarted(start: NodeTraceStart): Long

    public suspend fun nodeFinished(
        handle: Long,
        end: NodeTraceEnd,
    )

    public suspend fun log(
        level: String,
        message: String,
    )

    /** 运行内累计统计（token、调用数、转交数）。 */
    public suspend fun counters(delta: RunCounters)
}

/** 统计增量。 */
public data class RunCounters(
    val llmCalls: Int = 0,
    val llmTokens: Long = 0,
    val apiCalls: Int = 0,
    val handoffs: Int = 0,
)

/** 暂停/单步/断点控制；实现内部在需要时挂起。 */
public interface ControlPort {
    /** 每个节点执行前调用；暂停时挂起直到恢复。 */
    public suspend fun beforeNode(
        nodeId: String,
        nodeName: String?,
    )

    /** 节点最终失败且 `final=pause` 时挂起等待用户处理。 */
    public suspend fun pauseOnFailure(
        reason: PauseReason,
        nodeId: String,
        detail: String?,
    ): PauseResolution

    /** 连续失败达到阈值时暂停（用户处理后继续）。 */
    public suspend fun pauseOnConsecutive(
        count: Int,
        nodeId: String,
    ): PauseResolution

    /** 工具要求暂停（策略拦截）时挂起。 */
    public suspend fun pauseOnTool(
        control: ToolControl,
        nodeId: String,
    ): PauseResolution
}

/** 时间与随机源。 */
public interface FlowClock {
    public fun nowMs(): Long

    public val zone: ZoneId

    public fun random(
        lo: Double,
        hi: Double,
    ): Double
}

/** 解释器使用的全部端口。 */
public class FlowPorts(
    public val tools: ToolPort,
    public val observe: ObservePort,
    public val llm: LlmPort,
    public val api: ApiPort,
    public val agent: AgentPort,
    public val state: StatePort,
    public val notifications: NotificationPort,
    public val device: DevicePort,
    public val user: UserPort,
    public val flows: FlowResolver,
    public val trace: TracePort,
    public val control: ControlPort,
    public val clock: FlowClock,
)
