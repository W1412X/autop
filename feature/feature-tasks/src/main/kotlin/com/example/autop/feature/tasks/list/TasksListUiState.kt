/**
 * 任务列表界面状态（docs/tech/08 §4.3）。
 */
package com.example.autop.feature.tasks.list

/** 列表页状态。 */
public data class TasksListUiState(
    val tab: TasksTab = TasksTab.CONDITION,
    val rows: List<TaskRowUi> = emptyList(),
    val waiting: List<WaitingRunUi> = emptyList(),
    val pendingDeleteId: String? = null,
)

/** 一行任务。 */
public data class TaskRowUi(
    val id: String,
    val name: String,
    val triggerKind: TasksTab,
    /** 触发描述：时刻/日期时间、cron 表达式、事件源 id、空（手动）。 */
    val triggerDetail: String,
    /** 定时模式文案（0 = 自定义 cron/非定时）。 */
    val scheduleLabelRes: Int = 0,
    val cooldownMinutes: Int,
    val enabled: Boolean,
    /** 最近一次 Run 的状态名（无运行记录为 null）。 */
    val lastRunStatus: String?,
)

/** 一个等待确认的 Run（WAITING_USER）。 */
public data class WaitingRunUi(
    val runId: String,
    val taskName: String,
    val reason: String,
)
