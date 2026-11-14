/**
 * 时间线条目（docs/tech/08 §4.2）：由 `mergeTimeline` 从 ThreadEvent +
 * 实时 AgentEvent 增量归并生成。
 */
package com.example.autop.core.thread.ui

import com.example.autop.agent.PlanItem
import com.example.autop.agent.RunOutcome

/** 步骤状态（归并内核定义，对话页沿用同名）。 */
public typealias StepState = com.example.autop.agent.timeline.StepState

/** 状态横幅种类（归并内核定义，对话页沿用同名）。 */
public typealias BannerKind = com.example.autop.agent.timeline.BannerKind

/** 步骤快捷跳转入口（归并内核定义，对话页沿用同名）。 */
public typealias StepLink = com.example.autop.agent.timeline.StepLink

/** 时间线条目。 */
public sealed interface TimelineItem {
    /** 用户消息气泡（[isSteer] 为运行中补充指令）。 */
    public data class UserBubble(
        val text: String,
        val isSteer: Boolean,
        /** 随消息发送的图片（相对 filesDir 的 fileRef）。 */
        val images: List<String> = emptyList(),
    ) : TimelineItem

    /**
     * 助手文本（[streaming] 为流式未落盘增量，末尾显示光标）。
     * [meta] 为本轮 Run 结束的元信息行，附着在回复末尾（N183）。
     */
    public data class AssistantText(
        val text: String,
        val streaming: Boolean,
        val meta: RunMeta? = null,
    ) : TimelineItem

    /**
     * 思考块（折叠显示字数，docs/tech/08 §4.2 ThinkingBlock）。
     * [streaming] 为流式未落盘增量：思考中展开实时显示，落定后折叠（N172）。
     */
    public data class ThinkingBlock(
        val text: String,
        val streaming: Boolean = false,
    ) : TimelineItem

    /** 计划卡（PlanUpdated 覆盖式更新）。 */
    public data class PlanCard(
        val items: List<PlanItem>,
        /** 本次更新的说明（update_plan 的 explanation）。 */
        val explanation: String? = null,
    ) : TimelineItem

    /** 工具调用步骤行（StepRow）。 */
    public data class StepRow(
        val callId: String,
        /** 工具短名（线格式名）；注册 id 形态 `tool.<名>` 在归并时已归一（N178）。 */
        val toolId: String,
        val argsJson: String,
        val state: StepState,
        val durationMs: Long?,
        val resultText: String?,
        val observationLine: String?,
        val imageRef: String?,
        /** 流式进度文本（ToolProgress）。 */
        val progressText: String?,
        /** 链路编号（时间线内步骤序号，从 1 起；0 = 未编号，docs/tech/08 §4.2，N172）。 */
        val stepNo: Int = 0,
        /** 结果上的快捷跳转入口（任务/流程工具）。 */
        val links: List<StepLink> = emptyList(),
    ) : TimelineItem

    /** 连续读类步骤折叠行（"查看屏幕 ×n"，可展开查看组内链路步骤，N172）。 */
    public data class StepGroup(
        val steps: List<StepRow>,
    ) : TimelineItem {
        /** 折叠计数（"查看屏幕 ×n"）。 */
        public val count: Int get() = steps.size
    }

    /** 暂停 / 拦截 / 中断状态横幅。 */
    public data class Banner(
        val kind: BannerKind,
        val detail: String?,
    ) : TimelineItem

    /**
     * Run 结束元信息：任务状态 + 统计一行小字（N183，附着在回复末尾；
     * 末尾非助手文时作为独立条目）。
     */
    public data class RunMeta(
        val outcome: RunOutcome,
        val summary: String?,
        val stepCount: Int,
        val durationMs: Long,
        val outputTokens: Int,
        val errorCode: String? = null,
    ) : TimelineItem

    /** 分叉来源标记（ForkMarker）。 */
    public data class ForkMarker(
        val parentThreadId: String,
        val parentSeq: Long,
        /** 分支标题（如 买票(1.2)）。 */
        val title: String? = null,
    ) : TimelineItem
}

/**
 * 归并结果条目。
 *
 * @param item 条目内容。
 * @param seq 源事件 seq（流式未落盘条目为 null）。
 * @param forkable 该处是否为可分叉点（docs/tech/04 §3.11：无未配对 ToolCall 的
 * ASSISTANT / TOOL 批次结束处）。
 */
public data class TimelineEntry(
    val item: TimelineItem,
    val seq: Long?,
    val forkable: Boolean,
)
