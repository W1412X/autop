/**
 * `tool.dismiss_popup`（docs/design/flows-recording-recovery.md R6）：关闭遮挡当前页面的弹窗。
 * 只点击规则允许的关闭按钮；找不到返回 `device.popup_not_found`。
 * 按最近观测的选择方式观测找按钮（视觉伪树跳过窗口筛选），点击后用收尾观测复核弹窗是否仍在。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.GestureController
import com.example.autop.device.ObserveMode
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.popup.PopupFinder
import com.example.autop.device.impl.ObserveSelection
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.deviceHistory
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
import com.example.autop.device.impl.observation.ScreenObserver
import com.example.autop.device.impl.popup.PopupRulesLoader
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** prepare 生成的执行载荷：选中的节点快照、落点与依据。 */
private data class PopupAction(
    val snapshot: ScreenSnapshot,
    val x: Int,
    val y: Int,
    val reason: String,
    /** a11y 快照只在弹窗窗口内找关闭按钮；视觉伪树（单全屏窗口）跳过窗口筛选。 */
    val windowScoped: Boolean,
)

/** 关闭弹窗工具。 */
@Singleton
public class DismissPopupTool
    @Inject
    constructor(
        private val gestures: GestureController,
        private val subjects: PolicySubjectBuilder,
        private val epilogue: ActionEpilogue,
        private val rules: PopupRulesLoader,
        private val observer: ScreenObserver,
        private val trace: ExecutionTraceRenderer,
        private val prompts: PromptRepository,
    ) : ScreenActionTool {
        override val id: String = ID

        override val spec = DeviceToolSpecs.dismissPopup

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val selection = ctx.deviceHistory().last?.selection ?: ObserveSelection.Auto
            val observed =
                when (val got = observer.observe(ctx, selection, full = false)) {
                    is Outcome.Err -> return got
                    is Outcome.Ok -> got.value
                }
            val windowScoped = observed.screen.model.mode == ObserveMode.A11Y
            val pick =
                PopupFinder.find(observed.snapshot, rules.get(), windowScoped)
                    ?: return Outcome.Err(
                        AppError(
                            code = DeviceErrorCodes.POPUP_NOT_FOUND,
                            message = "没有发现可关闭的弹窗",
                            hint = prompts.text(PROMPT_NOT_FOUND_HINT).takeIf { it.isNotBlank() },
                        ),
                    )
            val center = pick.node.bounds
            val action =
                PopupAction(
                    snapshot = observed.snapshot,
                    x = (center.l + center.r) / 2,
                    y = (center.t + center.b) / 2,
                    reason = pick.reason,
                    windowScoped = windowScoped,
                )
            val extra = mapOf(EXTRA_REASON to pick.reason)
            return Outcome.Ok(PreparedAction(subjects.build(ID, observed.snapshot, null, extra, ctx.config), action))
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val popup = action.payload as PopupAction
            val injected = gestures.tap(popup.x, popup.y)
            if (!injected.ok) return errorOutput(injectFailed("关闭弹窗的点击"))
            val execution =
                trace.executionLine(
                    ExecMethod.GESTURE,
                    channel = injected.channel,
                    shellSkip = injected.shellSkip,
                )
            // 坐标手势点击：回放记录标 execution=gesture 并带实际落点（docs/tech/05 §13）。
            val result =
                epilogue.finishWithObservation(
                    ctx,
                    "已提交弹窗关闭按钮点击（依据：${popup.reason}）",
                    null,
                    ReplayInput(
                        ID,
                        JsonObject(emptyMap()),
                        gesture = gestureJson(popup.x, popup.y, TAP_DURATION_MS),
                    ),
                )
            val verify =
                result.observed?.snapshot?.let { post ->
                    val still = PopupFinder.find(post, rules.get(), popup.windowScoped) != null
                    Block.Text(prompts.text(if (still) PROMPT_STILL_THERE else PROMPT_CLOSED))
                }
            return result.output.copy(content = result.output.content + listOfNotNull(Block.Text(execution), verify))
        }

        private companion object {
            const val ID = "tool.dismiss_popup"
            const val EXTRA_REASON = "popupReason"
            const val TAP_DURATION_MS = 0L
            const val PROMPT_NOT_FOUND_HINT = "device.popup.not_found_hint"
            const val PROMPT_CLOSED = "device.popup.closed"
            const val PROMPT_STILL_THERE = "device.popup.still_there"
        }
    }
