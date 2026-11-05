/**
 * tool.notify_user（docs/tech/07 §7）：向用户发通知（写系统）。注意：POST_NOTIFICATIONS
 * 未授予时系统静默丢弃，恒返回 ok（真机验证项，questions.md N172）。
 */
package com.example.autop.task.impl.tools

import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.task.impl.notify.UserNotifierImpl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** 通知用户工具。 */
@Singleton
public class NotifyUserTool
    @Inject
    constructor(
        private val notifier: UserNotifierImpl,
    ) : Tool {
        override val id: String = "tool.notify_user"
        override val spec = TaskToolSpecs.notifyUser
        override val access: ToolAccess = ToolAccess.WRITE_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val title = args["title"]?.jsonPrimitive?.content ?: "通知"
            val text = args["text"]?.jsonPrimitive?.content.orEmpty()
            notifier.notifyMessage(title = title, text = text, runId = ctx.runId)
            return textOutput("ok")
        }
    }
