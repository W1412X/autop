/**
 * 任务域导航回调（docs/tech/08 §3：feature 之间不互相引用，路由由 app 组装）。
 */
package com.example.autop.feature.tasks

/** 任务页导航回调。 */
public interface TasksNavigator {
    /** 打开任务编辑页（[taskId] 为 null = 新建）。 */
    public fun openEdit(taskId: String?)

    /** 打开任务详情页。 */
    public fun openDetail(taskId: String)

    /** 打开 Run 详情页。 */
    public fun openRun(runId: String)

    /** 返回上一页。 */
    public fun navigateBack()
}
