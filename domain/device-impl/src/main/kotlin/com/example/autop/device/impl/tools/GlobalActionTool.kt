/**
 * `tool.global_action`（docs/tech/05 §5）：执行系统全局动作（back/home/recents/…）。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Outcome
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.GestureController
import com.example.autop.device.GlobalAction
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** prepare 生成的执行载荷。 */
private data class GlobalActionPayload(
    val action: GlobalAction,
    val replayArgs: JsonObject,
)

/** 全局动作工具。 */
@Singleton
public class GlobalActionTool
    @Inject
    constructor(
        private val screenReader: ScreenReader,
        private val gestures: GestureController,
        private val subjects: PolicySubjectBuilder,
        private val epilogue: ActionEpilogue,
    ) : ScreenActionTool {
        override val id: String = "tool.global_action"

        override val spec = DeviceToolSpecs.globalAction

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> =
            when (val got = screenReader.snapshot()) {
                is Outcome.Err -> got
                is Outcome.Ok -> prepareWith(got.value, args)
            }

        private fun prepareWith(
            snap: ScreenSnapshot,
            args: JsonObject,
        ): Outcome<PreparedAction> {
            val name = args.str("action").orEmpty()
            val action = actionOf(name)
            return if (action == null) {
                Outcome.Err(AppError(code = CommonErrorCodes.INVALID_ARGUMENT, message = "未知的全局动作 $name"))
            } else {
                val subject = subjects.build(ID, snap, null, mapOf(EXTRA_ACTION to name))
                Outcome.Ok(PreparedAction(subject, GlobalActionPayload(action, replayArgsOf(args, hit = null))))
            }
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val payload = action.payload as GlobalActionPayload
            val pre = epilogue.begin()
            val ok = gestures.globalAction(payload.action)
            if (!ok) {
                return toolError(DeviceErrorCodes.ACTION_FAILED, "全局动作 ${payload.action.name} 执行失败")
            }
            return epilogue.finish(
                ctx,
                "已执行 ${payload.action.name}",
                null,
                ReplayInput(ID, pre, payload.replayArgs, gesture = null),
            )
        }

        private fun actionOf(name: String): GlobalAction? =
            when (name) {
                "back" -> GlobalAction.BACK
                "home" -> GlobalAction.HOME
                "recents" -> GlobalAction.RECENTS
                "notifications" -> GlobalAction.NOTIFICATIONS
                "quick_settings" -> GlobalAction.QUICK_SETTINGS
                "lock" -> GlobalAction.LOCK_SCREEN
                else -> null
            }

        private companion object {
            const val ID = "tool.global_action"
            const val EXTRA_ACTION = "action"
        }
    }
