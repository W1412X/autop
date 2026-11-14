/**
 * 任务域导航回调（docs/tech/08 §3：feature 之间不互相引用，路由由 app 组装）。
 */
package com.example.autop.feature.tasks

import com.example.autop.agent.timeline.StepLink

/** 任务页导航回调。 */
public interface TasksNavigator {
    /** 打开任务编辑页（[taskId] 为 null = 新建）。 */
    public fun openEdit(taskId: String?)

    /** 打开 AI 创建任务页；[taskId] 非空时修改该任务。 */
    public fun openTaskAuthor(taskId: String? = null)

    /** 打开任务详情页。 */
    public fun openDetail(taskId: String)

    /** 打开 Run 详情页。 */
    public fun openRun(runId: String)

    /** 打开流程创建入口（AI 创建 / 手动创建 / 导入，由 app 连接 feature-flows）。 */
    public fun openFlowCreate()

    /** 返回上一页。 */
    public fun navigateBack()

    /** 打开过程里的快捷入口（任务详情、编辑、AI 修改，流程入口）。 */
    public fun openStepLink(link: StepLink)

    /** 打开一个对话线程（分叉标记指向的原会话）。 */
    public fun openThread(threadId: String)
}
