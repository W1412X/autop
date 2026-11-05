/**
 * 事件列表的只读查询（docs/tech/04 §3.2/§3.6）：计划、真实用户消息与文本提取，
 * TurnLoop、压缩装配与 fork 共用。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.PlanItem
import com.example.autop.agent.PlanStatus
import com.example.autop.agent.PlanUpdated
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role

/** 最新计划（最近一条 PlanUpdated；无则空）。 */
internal fun List<ThreadEvent>.latestPlanItems(): List<PlanItem> =
    lastOrNull { it.payload is PlanUpdated }
        ?.let { (it.payload as PlanUpdated).items }
        .orEmpty()

/** 真实用户消息（INPUT/STEER，不含 INJECTED），新→旧，带 seq。 */
internal fun List<ThreadEvent>.userMessagesNewestFirst(): List<Pair<Long, Msg>> =
    mapNotNull { event ->
        val payload = event.payload as? UserMessage ?: return@mapNotNull null
        if (payload.kind == UserMessageKind.INJECTED) return@mapNotNull null
        event.seq to Msg(Role.USER, payload.blocks)
    }.asReversed()

/** 计划的确定性渲染（每行一项）。 */
internal fun List<PlanItem>.planText(): String = joinToString("\n") { "- [${it.status.planMark()}] ${it.step}" }

private fun PlanStatus.planMark(): String =
    when (this) {
        PlanStatus.PENDING -> "pending"
        PlanStatus.IN_PROGRESS -> "doing"
        PlanStatus.DONE -> "done"
        PlanStatus.DROPPED -> "dropped"
    }

/** 消息中最后一个 Text 块（模型最后的文本）。 */
internal fun lastTextOf(msg: Msg): String =
    msg.blocks
        .filterIsInstance<Block.Text>()
        .lastOrNull()
        ?.text
        .orEmpty()
