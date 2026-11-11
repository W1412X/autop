/**
 * 线程事件行（docs/tech/04 §2.1）：持久化与推送共用的单元。
 */
package com.example.autop.agent

import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId

/**
 * 一条事件（ADR-0021）：[seq] 在整个库内全局唯一，[threadId] 为创建它的分支（归属），[payload] 为具体载荷。
 * 历史是从分支头沿父边回溯的路径；[forkable] 为持久化的可分叉标志。
 */
public data class ThreadEvent(
    val threadId: ThreadId,
    val seq: Long,
    val runId: RunId?,
    val createdAt: Long,
    val payload: EventPayload,
    /** 持久化的可分叉标志（ADR-0021）：路径上无未配对调用，且为助手消息或工具结果。 */
    val forkable: Boolean = false,
)
