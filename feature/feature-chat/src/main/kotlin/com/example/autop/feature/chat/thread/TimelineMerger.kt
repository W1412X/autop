/**
 * 时间线归并（docs/tech/08 §4.2/§7）：把持久化 [ThreadEvent] 与实时 [AgentEvent]
 * 增量归并为 [TimelineEntry] 列表。纯函数、全量重算（事件量可控，换取简单可靠），
 * 供 ViewModel 与单测直接驱动。
 */
package com.example.autop.feature.chat.thread

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.Baseline
import com.example.autop.agent.PlanUpdated
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunNoteAdded
import com.example.autop.agent.RunPaused
import com.example.autop.agent.RunResumed
import com.example.autop.agent.RunStarted
import com.example.autop.agent.ThreadCreated
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.protocol.displayTextOf
import com.example.autop.llm.Block

/** 读类工具：连续成功时折叠为 [TimelineItem.StepGroup]（docs/tech/08 §4.2）。 */
internal val READ_TOOLS: Set<String> = setOf("observe", "screenshot")

/** 注册工具 id 前缀（`ToolResultEvent.toolId` 为 `tool.<名>`，归并时归一为短名，N178）。 */
private const val TOOL_ID_PREFIX: String = "tool."

/**
 * 归并时间线。
 *
 * @param history 已持久化事件（按 seq 升序）。
 * @param live 实时增量（[AgentEvent.Persisted] 忽略；文本增量按"持久化到达即作废"由
 * 调用方清缓存，避免与历史重复）。
 * @param stepGroupThreshold 连续读类步骤折叠阈值（`ui.chat.stepGroupThreshold`）。
 */
public fun mergeTimeline(
    history: List<ThreadEvent>,
    live: List<AgentEvent>,
    stepGroupThreshold: Int,
): List<TimelineEntry> {
    val entries = mutableListOf<TimelineEntry>()
    var unpaired = 0
    var outputTokens = 0
    var startedAt: Long? = null
    var finished: ThreadEvent? = null
    history.forEach { event ->
        when (val payload = event.payload) {
            is AssistantMessage -> outputTokens += payload.usage?.outputTokens ?: 0
            is RunStarted -> startedAt = event.createdAt
            is RunFinished -> finished = event
            else -> Unit
        }
        unpaired = appendPayload(entries, event, unpaired)
    }
    live.forEach { appendLive(entries, it) }
    appendResult(entries, finished, startedAt, outputTokens)
    return foldGroups(numberSteps(entries), stepGroupThreshold)
}

/** 单个持久化事件 → 条目；返回更新后的未配对 ToolCall 数。 */
private fun appendPayload(
    entries: MutableList<TimelineEntry>,
    event: ThreadEvent,
    unpaired: Int,
): Int =
    when (val payload = event.payload) {
        is ThreadCreated -> {
            appendForkMarker(entries, event, payload)
            unpaired
        }
        is UserMessage -> {
            appendUserBubble(entries, event, payload)
            unpaired
        }
        is AssistantMessage -> appendMessage(entries, event, payload, unpaired)
        is ToolResultEvent -> applyToolResult(entries, event, payload, unpaired)
        is PlanUpdated -> {
            if (entries.lastOrNull()?.item is TimelineItem.PlanCard) {
                entries.removeAt(entries.lastIndex)
            }
            val card = TimelineItem.PlanCard(payload.items, payload.explanation)
            entries.add(TimelineEntry(card, event.seq, forkable = false))
            unpaired
        }
        is RunPaused -> {
            bannerKindOf(payload.reason)?.let { kind ->
                entries.add(
                    TimelineEntry(TimelineItem.Banner(kind, payload.detail), event.seq, forkable = false),
                )
            }
            unpaired
        }
        is RunResumed -> {
            val index = entries.indexOfLast { isUnresolvedBanner(it.item) }
            if (index >= 0) {
                entries.removeAt(index)
            }
            unpaired
        }
        is RunStarted,
        is RunFinished,
        is Baseline,
        is RunNoteAdded,
        -> unpaired
    }

/** 分叉来源线程的 ThreadCreated → ForkMarker（仅分叉线程有标记）。 */
private fun appendForkMarker(
    entries: MutableList<TimelineEntry>,
    event: ThreadEvent,
    payload: ThreadCreated,
) {
    val parent = payload.parentThreadId ?: return
    entries.add(
        TimelineEntry(
            TimelineItem.ForkMarker(parent.value, payload.parentSeq ?: 0),
            event.seq,
            forkable = false,
        ),
    )
}

/** 用户消息 → UserBubble（INJECTED 的注入上下文不进时间线）。 */
private fun appendUserBubble(
    entries: MutableList<TimelineEntry>,
    event: ThreadEvent,
    payload: UserMessage,
) {
    if (payload.kind == UserMessageKind.INJECTED) return
    val text = payload.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
    val images = payload.blocks.filterIsInstance<Block.Image>().map { it.fileRef }
    entries.add(
        TimelineEntry(
            TimelineItem.UserBubble(text, payload.kind == UserMessageKind.STEER, images),
            event.seq,
            forkable = false,
        ),
    )
}

private fun appendMessage(
    entries: MutableList<TimelineEntry>,
    event: ThreadEvent,
    payload: AssistantMessage,
    unpaired: Int,
): Int {
    var pairs = unpaired
    payload.blocks.forEach { block ->
        when (block) {
            is Block.Text ->
                // 回退协议/工具标记文本不冒充正文（displayTextOf 过滤，落盘原文不改）。
                displayTextOf(block.text).takeIf { it.isNotBlank() }?.let { shown ->
                    entries.add(
                        TimelineEntry(TimelineItem.AssistantText(shown, streaming = false), event.seq, false),
                    )
                }
            is Block.Thinking ->
                entries.add(
                    TimelineEntry(
                        TimelineItem.ThinkingBlock(block.text, streaming = false),
                        event.seq,
                        forkable = false,
                    ),
                )
            is Block.ToolCall -> {
                entries.add(
                    TimelineEntry(
                        TimelineItem.StepRow(
                            callId = block.id,
                            toolId = block.name,
                            argsJson = block.argsJson,
                            state = StepState.RUNNING,
                            durationMs = null,
                            resultText = null,
                            observationLine = null,
                            imageRef = null,
                            progressText = null,
                        ),
                        event.seq,
                        forkable = false,
                    ),
                )
                pairs += 1
            }
            is Block.Image,
            is Block.ToolResult,
            -> Unit
        }
    }
    if (pairs == unpaired && entries.isNotEmpty()) {
        val last = entries.last()
        if (last.seq == event.seq) {
            entries[entries.lastIndex] = last.copy(forkable = true)
        }
    }
    return pairs
}

private fun applyToolResult(
    entries: MutableList<TimelineEntry>,
    event: ThreadEvent,
    payload: ToolResultEvent,
    unpaired: Int,
): Int {
    val index = entries.indexOfLast { (it.item as? TimelineItem.StepRow)?.callId == payload.callId }
    val previousArgs = (entries.getOrNull(index)?.item as? TimelineItem.StepRow)?.argsJson ?: ""
    val step =
        TimelineItem.StepRow(
            callId = payload.callId,
            toolId = payload.toolId.removePrefix(TOOL_ID_PREFIX),
            argsJson = previousArgs,
            state = if (payload.ok) StepState.DONE else StepState.FAILED,
            durationMs = payload.durationMs,
            resultText =
                payload.blocks
                    .filterIsInstance<Block.Text>()
                    .firstOrNull()
                    ?.text,
            observationLine = payload.observation?.summaryLine,
            imageRef =
                payload.blocks
                    .filterIsInstance<Block.Image>()
                    .firstOrNull()
                    ?.fileRef,
            progressText = null,
        )
    val entry = TimelineEntry(step, event.seq, forkable = unpaired <= 1)
    if (index >= 0) {
        entries[index] = entry
    } else {
        entries.add(entry)
    }
    return (unpaired - 1).coerceAtLeast(0)
}

/**
 * Run 结束 → [TimelineItem.RunMeta] 元信息行；末条为助手文时附着其末尾（N183），
 * 否则作为独立条目。
 */
private fun appendResult(
    entries: MutableList<TimelineEntry>,
    finished: ThreadEvent?,
    startedAt: Long?,
    outputTokens: Int,
) {
    val event = finished ?: return
    val payload = event.payload as? RunFinished ?: return
    val trimmed = payload.summary?.trim()
    val duplicated =
        entries.any { entry ->
            (entry.item as? TimelineItem.AssistantText)?.text?.trim() == trimmed
        }
    val meta =
        TimelineItem.RunMeta(
            outcome = payload.status,
            summary = if (trimmed.isNullOrEmpty() || duplicated) null else payload.summary,
            stepCount = entries.count { it.item is TimelineItem.StepRow },
            durationMs = event.createdAt - (startedAt ?: event.createdAt),
            outputTokens = outputTokens,
        )
    val lastIndex = entries.lastIndex
    val lastText = entries.getOrNull(lastIndex)?.item as? TimelineItem.AssistantText
    if (lastText != null) {
        entries[lastIndex] = entries[lastIndex].copy(item = lastText.copy(meta = meta))
    } else {
        entries.add(TimelineEntry(meta, event.seq, forkable = false))
    }
}

/** 实时增量 → 追加或合并到流式条目。 */
private fun appendLive(
    entries: MutableList<TimelineEntry>,
    event: AgentEvent,
) {
    when (event) {
        is AgentEvent.TextDelta -> appendStream(entries, event.text, thinking = false)
        is AgentEvent.ThinkingDelta -> appendStream(entries, event.text, thinking = true)
        is AgentEvent.ToolCallStreaming ->
            entries.add(
                TimelineEntry(
                    TimelineItem.StepRow(
                        callId = "live-${event.index}",
                        toolId = event.name,
                        argsJson = "",
                        state = StepState.RUNNING,
                        durationMs = null,
                        resultText = null,
                        observationLine = null,
                        imageRef = null,
                        progressText = null,
                    ),
                    seq = null,
                    forkable = false,
                ),
            )
        is AgentEvent.ToolProgress -> applyToolProgress(entries, event)
        is AgentEvent.StateChanged,
        is AgentEvent.Persisted,
        -> Unit
    }
}

/** 流式文本合并：追加到同类型流式条目，否则新建（docs/tech/08 §7 测试项）。 */
private fun appendStream(
    entries: MutableList<TimelineEntry>,
    text: String,
    thinking: Boolean,
) {
    val last = entries.lastOrNull()?.takeIf { it.seq == null }
    val item = last?.item
    if (thinking && item is TimelineItem.ThinkingBlock) {
        entries[entries.lastIndex] = last.copy(item = item.copy(text = item.text + text))
    } else if (!thinking && item is TimelineItem.AssistantText && item.streaming) {
        entries[entries.lastIndex] = last.copy(item = item.copy(text = item.text + text))
    } else {
        val new =
            if (thinking) {
                TimelineItem.ThinkingBlock(text, streaming = true)
            } else {
                TimelineItem.AssistantText(text, streaming = true)
            }
        entries.add(TimelineEntry(new, seq = null, forkable = false))
    }
}

private fun applyToolProgress(
    entries: MutableList<TimelineEntry>,
    event: AgentEvent.ToolProgress,
) {
    val exact = entries.indexOfLast { (it.item as? TimelineItem.StepRow)?.callId == event.callId }
    val index =
        if (exact >= 0) {
            exact
        } else {
            entries.indexOfLast { (it.item as? TimelineItem.StepRow)?.state == StepState.RUNNING }
        }
    if (index < 0) return
    val step = entries[index].item as? TimelineItem.StepRow ?: return
    entries[index] = entries[index].copy(item = step.copy(progressText = event.text))
}
