/**
 * 任务详情页界面状态（docs/tech/08 §4.3）。
 */
package com.example.autop.feature.tasks.detail

/** 详情页状态。 */
public data class TaskDetailUiState(
    val taskId: String = "",
    val notFound: Boolean = false,
    val name: String = "",
    val goal: String = "",
    val triggerText: String = "",
    /** 定时模式文案（0 = 非定时/自定义 cron）。 */
    val triggerLabelRes: Int = 0,
    val needsScreen: Boolean = true,
    val lockedRemind: Boolean = true,
    val priority: Int = 0,
    val enabled: Boolean = true,
    /** 流程任务且流程功能关闭：失效，不能立即运行（ADR-0030）。 */
    val flowDisabled: Boolean = false,
    val runs: List<RunRowUi> = emptyList(),
    val feedback: TaskDetailFeedback? = null,
)

/** 一条运行记录（列表行）。 */
public data class RunRowUi(
    val runId: String,
    val statusName: String,
    val originName: String,
    val summary: String,
    val timeText: String,
)

/** 立即运行的反馈（成功标记或错误码）。 */
public data class TaskDetailFeedback(
    val started: Boolean,
    val errorCode: String? = null,
)
