/**
 * 任务域工具规格（docs/tech/07 §7）：tool.notify_user 与任务的查看 / 创建 / 更新。
 * 不提供删除工具：删除只能由用户在任务页操作。
 */
package com.example.autop.task.impl.tools

import com.example.autop.agent.ToolSpec
import com.example.autop.agent.toolSpec

/** 任务域工具规格。 */
public object TaskToolSpecs {
    /** tool.notify_user：向用户发一条通知（07 §7）。 */
    public val notifyUser: ToolSpec =
        toolSpec(
            name = "notify_user",
            descriptionPromptId = "tool.notify_user",
        ) {
            string("title")
            string("text")
        }

    /** tool.list_tasks：列出任务及状态。 */
    public val listTasks: ToolSpec = toolSpec(name = "list_tasks", descriptionPromptId = "tool.list_tasks") {}

    /**
     * tool.create_task：直接创建任务（默认启用）。[flowsEnabled] 为假时不提供 `flow` 参数
     * （流程 Beta 功能关闭，docs/adr/0030-beta-feature-switch.md）。
     */
    public fun createTask(flowsEnabled: Boolean = true): ToolSpec =
        toolSpec(name = "create_task", descriptionPromptId = "tool.create_task") {
            string("name", required = true)
            string("goal", required = true)
            string("trigger_type", enum = listOf("manual", "once", "cron", "condition"))
            string("at")
            string("cron")
            string("timezone")
            string("source")
            string("condition")
            if (flowsEnabled) string("flow")
            boolean("needs_screen")
            boolean("enabled")
        }

    /** tool.update_task：按 id 或名称修改任务（不可删除）；[flowsEnabled] 含义同 [createTask]。 */
    public fun updateTask(flowsEnabled: Boolean = true): ToolSpec =
        toolSpec(name = "update_task", descriptionPromptId = "tool.update_task") {
            string("task", required = true)
            string("name")
            string("goal")
            string("trigger_type", enum = listOf("manual", "once", "cron", "condition"))
            string("at")
            string("cron")
            string("timezone")
            string("source")
            string("condition")
            if (flowsEnabled) string("flow")
            boolean("needs_screen")
            boolean("enabled")
        }
}
