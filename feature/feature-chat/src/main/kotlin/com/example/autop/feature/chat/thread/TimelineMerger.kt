/**
 * 时间线归并（docs/tech/08 §4.2/§7）：公共归并内核 `mergeCore` 的对话页映射——
 * 中立 `CoreEntry` → [TimelineEntry]，再做读类步骤折叠与链路编号。
 * 归并口径（按轮窗口统计、结束元信息结算、callId 配对转态、流式合并）单点维护
 * 在 `agent-api` 归并内核，悬浮层 `ProgressLogic` 共用；此处只做 UI 模型转换。
 */
package com.example.autop.feature.chat.thread

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.timeline.CoreEntry
import com.example.autop.agent.timeline.CoreRow
import com.example.autop.agent.timeline.mergeCore

/** 读类工具：连续成功时折叠为 [TimelineItem.StepGroup]（docs/tech/08 §4.2）。 */
internal val READ_TOOLS: Set<String> = setOf("observe", "screenshot")

/** 注册工具 id 前缀（`ToolResultEvent.toolId` 为 `tool.<名>`，映射时归一为短名，N178）。 */
private const val TOOL_ID_PREFIX: String = "tool."

/**
 * 归并时间线。
 *
 * @param history 已持久化事件（按 seq 升序）。
 * @param live 实时增量（文本增量按"持久化到达即作废"由调用方清缓存，避免与历史重复）。
 * @param stepGroupThreshold 连续读类步骤折叠阈值（`ui.chat.stepGroupThreshold`）。
 */
public fun mergeTimeline(
    history: List<ThreadEvent>,
    live: List<AgentEvent>,
    stepGroupThreshold: Int,
): List<TimelineEntry> {
    val entries = mergeCore(history, live).map { it.toEntry() }
    return foldGroups(numberSteps(entries), stepGroupThreshold)
}

private fun CoreEntry.toEntry(): TimelineEntry = TimelineEntry(row.toItem(), seq, forkable)

private fun CoreRow.toItem(): TimelineItem =
    when (this) {
        is CoreRow.User -> TimelineItem.UserBubble(text, isSteer, images)
        is CoreRow.Assistant -> TimelineItem.AssistantText(text, streaming, meta?.toMeta())
        is CoreRow.Thinking -> TimelineItem.ThinkingBlock(text, streaming)
        is CoreRow.Step -> toStepRow()
        is CoreRow.Plan -> TimelineItem.PlanCard(items, explanation)
        is CoreRow.RunMeta -> toMeta()
        is CoreRow.Banner -> TimelineItem.Banner(kind, detail)
        is CoreRow.ForkMarker -> TimelineItem.ForkMarker(parentThreadId, parentSeq, title)
    }

private fun CoreRow.RunMeta.toMeta(): TimelineItem.RunMeta =
    TimelineItem.RunMeta(
        outcome = outcome,
        summary = summary,
        stepCount = stepCount,
        durationMs = durationMs,
        outputTokens = outputTokens,
    )

private fun CoreRow.Step.toStepRow(): TimelineItem.StepRow =
    TimelineItem.StepRow(
        callId = callId,
        toolId = toolId.removePrefix(TOOL_ID_PREFIX),
        argsJson = argsJson,
        state = state,
        durationMs = durationMs,
        resultText = resultText,
        observationLine = observationLine,
        imageRef = imageRef,
        progressText = progressText,
        links = links,
    )
