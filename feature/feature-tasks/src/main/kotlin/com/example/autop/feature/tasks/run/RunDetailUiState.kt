/**
 * Run 详情页界面状态（docs/tech/08 §4.3）：只读状态/摘要/触发上下文 + 过程时间线。
 */
package com.example.autop.feature.tasks.run

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
    val timeline: List<TimelineUi> = emptyList(),
    val feedback: RunDetailFeedback? = null,
    /** 状态为 INTERRUPTED 时可“继续执行”（docs/tech/04 §3.11）。 */
    val canResume: Boolean = false,
)

/** 时间线上的一项。 */
public data class TimelineUi(
    val kind: TimelineKind,
    val detail: String,
)

/** 时间线条目种类。 */
public enum class TimelineKind {
    USER,
    ASSISTANT,
    TOOL,
    STARTED,
    FINISHED,
    PAUSED,
    RESUMED,
    NOTE,
    PLAN,
    OTHER,
}

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
