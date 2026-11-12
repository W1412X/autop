/**
 * tool.notify_user（docs/tech/07 §7）：向用户发通知（写系统）。
 * POST_NOTIFICATIONS 未授予时通知不会显示，返回失败（tool_optimize D19）。
 */
package com.example.autop.task.impl.tools

import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.PermissionChecks
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.task.TaskErrorCodes
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
        private val permissions: PermissionChecks,
        private val prompts: PromptRepository,
    ) : Tool {
        override val id: String = "tool.notify_user"
        override val spec = TaskToolSpecs.notifyUser
        override val access: ToolAccess = ToolAccess.WRITE_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            if (!permissions.canPostNotifications()) {
                return failure(
                    TaskErrorCodes.NOTIFICATION_DENIED,
                    "POST_NOTIFICATIONS not granted",
                    render(PROMPT_DENIED_HINT),
                )
            }
            val title = args["title"]?.jsonPrimitive?.content ?: "通知"
            val text = args["text"]?.jsonPrimitive?.content.orEmpty()
            notifier.notifyMessage(title = title, text = text, runId = ctx.runId)
            return textOutput(render(PROMPT_SENT, mapOf(VAR_TITLE to title)))
        }

        private fun render(
            id: String,
            vars: Map<String, String> = emptyMap(),
        ): String = (prompts.render(id, vars) as? Outcome.Ok)?.value?.text.orEmpty()

        private companion object {
            const val PROMPT_SENT = "task.notify.sent"
            const val PROMPT_DENIED_HINT = "task.notify.denied_hint"
            const val VAR_TITLE = "title"
        }
    }
