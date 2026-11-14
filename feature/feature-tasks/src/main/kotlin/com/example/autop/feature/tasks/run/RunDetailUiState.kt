/**
 * Run 详情页界面状态（docs/tech/08 §4.3）：只读状态/摘要/触发上下文 + 过程时间线（ADR-0031）。
 */
package com.example.autop.feature.tasks.run

import com.example.autop.core.thread.ui.TimelineEntry

/** Run 详情页状态。 */
public data class RunDetailUiState(
    val runId: String = "",
    val notFound: Boolean = false,
    val statusName: String = "",
    val originName: String = "",
    val summary: String = "",
    val triggerContext: List<String> = emptyList(),
    val startedText: String = "",
    val endedText: String = "",
    /** 过程时间线：与对话页相同的条目模型（ADR-0031）。 */
    val timeline: List<TimelineEntry> = emptyList(),
    /** 长按过程条目打开的菜单；null 表示未打开。 */
    val entryMenu: RunEntryMenu? = null,
    /** 原始数据弹窗的文本；null 表示未打开。 */
    val rawText: String? = null,
    val feedback: RunDetailFeedback? = null,
    /** 状态为 INTERRUPTED 时可“继续执行”（docs/tech/04 §3.11）。 */
    val canResume: Boolean = false,
)

/** 长按过程条目的菜单：[seq] 为条目序号，[forkable] 为可否从这里分叉。 */
public data class RunEntryMenu(
    val seq: Long,
    val forkable: Boolean,
)

/** “在此基础上继续”/“继续执行”的反馈（成功标记或错误码）。 */
public data class RunDetailFeedback(
    val kind: RunDetailFeedbackKind = RunDetailFeedbackKind.FORK,
    val continued: Boolean,
    val errorCode: String? = null,
)

/** 反馈来源动作。 */
public enum class RunDetailFeedbackKind {
    FORK,
    RESUME,
}
