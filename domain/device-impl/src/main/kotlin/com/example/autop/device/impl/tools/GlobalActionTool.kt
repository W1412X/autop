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
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.prompt.PromptRepository
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
    val waitMs: Long?,
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
        private val trace: ExecutionTraceRenderer,
        private val prompts: PromptRepository,
    ) : ScreenActionTool {
        override val id: String = "tool.global_action"

        override val spec = DeviceToolSpecs.globalAction

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> =
            // N193 空树降级：全局动作不依赖节点树，降级快照即可生成策略主体；
            // 空树应用的恢复路径（back/home）不能被节点树卡死。
            when (val got = screenReader.snapshotOrDegraded()) {
                is Outcome.Err -> got
                is Outcome.Ok -> prepareWith(got.value, args, ctx.config)
            }

        private fun prepareWith(
            snap: ScreenSnapshot,
            args: JsonObject,
            config: ConfigSnapshot,
        ): Outcome<PreparedAction> {
            val name = args.str("action").orEmpty()
            val action = actionOf(name)
            return if (action == null) {
                Outcome.Err(AppError(code = CommonErrorCodes.INVALID_ARGUMENT, message = "未知的全局动作 $name"))
            } else {
                val subject = subjects.build(ID, snap, null, mapOf(EXTRA_ACTION to name), config)
                Outcome.Ok(
                    PreparedAction(
                        subject,
                        GlobalActionPayload(action, waitMsOf(args), replayArgsOf(args, hit = null)),
                    ),
                )
            }
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val payload = action.payload as GlobalActionPayload
            val pre = epilogue.preSample(ctx)
            val result = gestures.globalAction(payload.action)
            if (!result.ok) {
                return toolError(
                    DeviceErrorCodes.ACTION_FAILED,
                    "全局动作 ${payload.action.name} 执行失败",
                    prompts.text(PROMPT_FAILED_HINT),
                )
            }
            val traceLine =
                trace.executionLine(
                    ExecMethod.GLOBAL_ACTION,
                    channel = result.channel,
                    shellSkip = result.shellSkip,
                )
            return epilogue.finish(
                ctx,
                prompts.text(PROMPT_RESULT, mapOf(VAR_ACTION to payload.action.name)),
                payload.waitMs,
                ReplayInput(ID, payload.replayArgs, gesture = null),
                pre,
                trace = listOf(traceLine),
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
            const val PROMPT_RESULT = "device.global_action.result"
            const val PROMPT_FAILED_HINT = "device.global_action.failed_hint"
            const val VAR_ACTION = "action"
        }
    }
