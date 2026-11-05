/**
 * 任务列表用户意图（docs/tech/08 §4.3）。
 */
package com.example.autop.feature.tasks.list

/** 列表页意图。 */
public sealed interface TasksListAction {
    /** 切换分组标签。 */
    public data class SetTab(
        val tab: TasksTab,
    ) : TasksListAction

    /** 打开详情。 */
    public data class OpenDetail(
        val taskId: String,
    ) : TasksListAction

    /** 打开编辑。 */
    public data class OpenEdit(
        val taskId: String,
    ) : TasksListAction

    /** 新建任务。 */
    public data object CreateTask : TasksListAction

    /** 启停任务。 */
    public data class ToggleEnabled(
        val taskId: String,
        val enabled: Boolean,
    ) : TasksListAction

    /** 请求删除（弹确认框）。 */
    public data class RequestDelete(
        val taskId: String,
    ) : TasksListAction

    /** 确认删除。 */
    public data object ConfirmDelete : TasksListAction

    /** 取消删除。 */
    public data object DismissDelete : TasksListAction

    /** 等待提醒：执行。 */
    public data class ExecuteWaiting(
        val runId: String,
    ) : TasksListAction

    /** 等待提醒：稍后。 */
    public data class SnoozeWaiting(
        val runId: String,
    ) : TasksListAction

    /** 等待提醒：跳过。 */
    public data class SkipWaiting(
        val runId: String,
    ) : TasksListAction
}

/** 列表分组。 */
public enum class TasksTab {
    CONDITION,
    SCHEDULE,
    MANUAL,
}
