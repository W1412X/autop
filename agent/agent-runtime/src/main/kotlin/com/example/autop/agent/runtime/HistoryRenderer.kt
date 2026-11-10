/**
 * 历史渲染（docs/tech/04 §3.4）：事件 → RenderItem → 规则 → `List<Msg>`。
 *
 * 与文档伪代码的差异（见 questions.md）：规则先于“合并相邻 TOOL 消息”执行——
 * RenderItem 每项只携带一个工具结果的 seq/observation 元数据，先合并会让
 * 降级规则失去 per-result 的 seq。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.Baseline
import com.example.autop.agent.HistoryRenderRule
import com.example.autop.agent.RenderItem
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RuntimeStateReported
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role

/** 历史渲染器。 */
internal class HistoryRenderer(
    private val store: ThreadStore,
    private val rules: Set<HistoryRenderRule>,
) {
    private val rulesById: Map<String, HistoryRenderRule> = rules.associateBy { it.id }

    /** 当前 live 视图的消息（从最近 Baseline 起）。 */
    suspend fun messages(
        threadId: ThreadId,
        config: ConfigSnapshot,
    ): List<Msg> = live(threadId, config).map { it.msg }

    /** 当前 live 视图的渲染项（压缩等需要 seq 元数据时用）。 */
    suspend fun live(
        threadId: ThreadId,
        config: ConfigSnapshot,
    ): List<RenderItem> = renderEvents(store.eventsSinceLatestBaseline(threadId), config)

    /** 从事件列表渲染（events 从 Baseline 开始；fork 复用）。 */
    fun renderEvents(
        events: List<ThreadEvent>,
        config: ConfigSnapshot,
    ): List<RenderItem> {
        var items = toItems(events)
        for (ruleId in config[AgentConfig.HISTORY].rules) {
            items = rulesById[ruleId]?.apply(items, config) ?: items
        }
        return repairToolPairing(mergeToolMessages(items))
    }

    @Suppress("LongMethod") // 事件→渲染项是一张穷举映射表，拆散反而难核对
    private fun toItems(events: List<ThreadEvent>): List<RenderItem> {
        val items = mutableListOf<RenderItem>()
        for (event in events) {
            val seq = event.seq
            when (val payload = event.payload) {
                is Baseline ->
                    payload.messages.forEach { msg ->
                        items += RenderItem(seq = seq, msg = msg, observation = null, toolId = null)
                    }
                is UserMessage ->
                    items +=
                        RenderItem(
                            seq = seq,
                            msg = Msg(Role.USER, payload.blocks),
                            observation = null,
                            toolId = null,
                        )
                is AssistantMessage ->
                    items +=
                        RenderItem(
                            seq = seq,
                            msg = Msg(Role.ASSISTANT, payload.blocks, producedBy = payload.modelProfileId),
                            observation = null,
                            toolId = null,
                        )
                is RuntimeStateReported ->
                    items +=
                        RenderItem(
                            seq = seq,
                            msg = Msg(Role.USER, listOf(Block.Text(payload.text))),
                            observation = null,
                            toolId = null,
                        )
                is RunFinished -> {
                    // Run 结束说明进 live（WP8.2）：同线程后续 Run 的模型能看到上一个任务没有完成。
                    val note = payload.liveNote
                    if (note != null) {
                        items +=
                            RenderItem(
                                seq = seq,
                                msg = Msg(Role.USER, listOf(Block.Text(note))),
                                observation = null,
                                toolId = null,
                            )
                    }
                }
                is ToolResultEvent ->
                    items +=
                        RenderItem(
                            seq = seq,
                            msg =
                                Msg(
                                    Role.TOOL,
                                    listOf(
                                        Block.ToolResult(
                                            callId = payload.callId,
                                            content = payload.blocks,
                                            isError = !payload.ok,
                                        ),
                                    ),
                                ),
                            observation = payload.observation,
                            toolId = payload.toolId,
                            fullBlocks = payload.fullBlocks,
                        )
                else -> Unit // 其他事件不进 live
            }
        }
        return items
    }

    /** 相邻 TOOL 消息合并为一条（多个 ToolResult 块），减少消息条数。 */
    private fun mergeToolMessages(items: List<RenderItem>): List<RenderItem> {
        val merged = mutableListOf<RenderItem>()
        for (item in items) {
            val last = merged.lastOrNull()
            if (last != null && last.msg.role == Role.TOOL && item.msg.role == Role.TOOL) {
                merged[merged.lastIndex] = last.copy(msg = last.msg.copy(blocks = last.msg.blocks + item.msg.blocks))
            } else {
                merged += item
            }
        }
        return merged
    }

    /**
     * 配对修复（N192）：孤儿 ToolCall（run 被取消/中断时 ToolResult 未落盘）补占位结果、
     * 缺失应答补齐、无主 ToolResult 剔除——任何事件序列渲染出的消息序列都能通过
     * MsgValidator。此前 cancel 留下的孤儿调用会让同线程下一个 Run 的请求直接触发
     * "user message before previous tool calls were answered" 崩溃（run_crashed）。
     */
    private fun repairToolPairing(items: List<RenderItem>): List<RenderItem> {
        val out = mutableListOf<RenderItem>()
        var pending: Set<String> = emptySet()
        for (item in items) {
            when (item.msg.role) {
                Role.ASSISTANT -> {
                    if (pending.isNotEmpty()) out += interruptedToolMsg(item.seq, pending)
                    out += item
                    pending =
                        item.msg.blocks
                            .filterIsInstance<Block.ToolCall>()
                            .mapTo(mutableSetOf()) { it.id }
                }
                Role.TOOL -> {
                    pairedToolItem(item, pending)?.let { out += it }
                    pending = emptySet()
                }
                Role.USER -> {
                    if (pending.isNotEmpty()) out += interruptedToolMsg(item.seq, pending)
                    out += item
                    pending = emptySet()
                }
            }
        }
        if (pending.isNotEmpty()) out += interruptedToolMsg(items.lastOrNull()?.seq ?: 0L, pending)
        return out
    }

    /** TOOL 消息与 pending 配对：保留命中结果（按 callId 去重）、缺失的补占位；全无主返回 null 丢弃。 */
    private fun pairedToolItem(
        item: RenderItem,
        pending: Set<String>,
    ): RenderItem? {
        val kept = LinkedHashMap<String, Block.ToolResult>()
        for (result in item.msg.blocks.filterIsInstance<Block.ToolResult>()) {
            if (result.callId in pending) kept.putIfAbsent(result.callId, result)
        }
        val missing = pending - kept.keys
        if (kept.isEmpty() && missing.isEmpty()) return null
        val blocks = kept.values.toList() + missing.map { interruptedResult(it) }
        return item.copy(msg = item.msg.copy(blocks = blocks))
    }

    /** 一整条“中断未返回”占位 TOOL 消息。 */
    private fun interruptedToolMsg(
        seq: Long,
        callIds: Set<String>,
    ): RenderItem =
        RenderItem(
            seq = seq,
            msg = Msg(Role.TOOL, callIds.map { interruptedResult(it) }),
            observation = null,
            toolId = null,
        )

    /** 单个占位 ToolResult（isError，内容为中断提示）。 */
    private fun interruptedResult(callId: String): Block.ToolResult =
        Block.ToolResult(
            callId = callId,
            content = listOf(Block.Text(INTERRUPTED_NOTE)),
            isError = true,
        )

    private companion object {
        const val INTERRUPTED_NOTE = "（该调用未返回结果：Run 被取消或中断）"
    }
}
