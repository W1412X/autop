/**
 * 补充指令队列（docs/tech/04 §3.2：steer 走 Channel(UNLIMITED)），
 * 从 RunController 拆出的单职责小类。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.UserMessageKind
import kotlinx.coroutines.channels.Channel

/** 一条排队消息（N192：steer 与 resume 恢复提示共用队列，落盘 kind 各自保留）。 */
internal data class QueuedSteer(
    val text: String,
    val kind: UserMessageKind,
)

/** steer 队列。 */
internal class SteerQueue {
    private val channel = Channel<QueuedSteer>(Channel.UNLIMITED)

    /** 排入一条补充指令（永不挂起）。 */
    fun send(
        text: String,
        kind: UserMessageKind = UserMessageKind.STEER,
    ) {
        channel.trySend(QueuedSteer(text, kind))
    }

    /** 取走当前排队的全部补充指令。 */
    fun drain(): List<QueuedSteer> {
        val drained = mutableListOf<QueuedSteer>()
        while (true) {
            val next = channel.tryReceive()
            if (next.isFailure) return drained
            drained += next.getOrThrow()
        }
    }
}
