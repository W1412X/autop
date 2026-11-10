/**
 * 运行时状态扩展点（agent-harness-overhaul WP3）：Run 开始给一份完整运行状态，
 * 之后只在非易变状态变化时以 USER 消息增量追加，system 在 Run 内保持不变。
 */
package com.example.autop.agent

import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.ConfigSnapshot

/** 一条运行时状态。 */
public data class StateEntry(
    /** 来源内唯一的稳定键，如 `shell`、`time`。 */
    val key: String,
    /** 给模型看的一行文字（已用模板渲染）。 */
    val text: String,
    /** 易变：单独变化不触发上报，只在有其它变化或全量上报时随同刷新（时间、电量、预算数字）。 */
    val volatile: Boolean = false,
)

/** 采集请求。 */
public data class StateRequest(
    val runId: RunId,
    val threadId: ThreadId,
    val config: ConfigSnapshot,
    val vars: Map<String, String>,
)

/**
 * 运行时状态来源（扩展点，id 前缀 `state.`）。实现必须廉价：不截图、不发网络请求、
 * 不做 IO 以外的阻塞操作；每轮采样前都会调用。线程约束：挂起函数主线程安全。
 */
public interface RuntimeStateSource {
    public val id: String

    public suspend fun entries(req: StateRequest): List<StateEntry>
}
