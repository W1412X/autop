/**
 * 任务域工具规格（docs/tech/07 §7）：tool.notify_user。
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
}
