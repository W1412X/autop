/**
 * 线程事件行（docs/tech/04 §2.1）：持久化与推送共用的单元。
 */
package com.example.autop.agent

import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId

/**
 * 一条线程事件：[seq] 在线程内自增（1 起），[payload] 为具体载荷。
 * 同一线程的 `(threadId, seq)` 唯一。
 */
public data class ThreadEvent(
    val threadId: ThreadId,
    val seq: Long,
    val runId: RunId?,
    val createdAt: Long,
    val payload: EventPayload,
)
