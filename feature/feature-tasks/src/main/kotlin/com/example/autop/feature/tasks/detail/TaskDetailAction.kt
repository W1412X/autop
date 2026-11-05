/**
 * 任务详情页用户意图（docs/tech/08 §4.3）。
 */
package com.example.autop.feature.tasks.detail

/** 详情页意图。 */
public sealed interface TaskDetailAction {
    /** 立即运行。 */
    public data object RunNow : TaskDetailAction

    /** 打开编辑页。 */
    public data object OpenEdit : TaskDetailAction

    /** 打开一条运行记录。 */
    public data class OpenRun(
        val runId: String,
    ) : TaskDetailAction

    /** 返回。 */
    public data object Back : TaskDetailAction
}
