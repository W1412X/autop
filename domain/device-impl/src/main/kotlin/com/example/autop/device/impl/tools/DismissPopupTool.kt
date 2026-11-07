/**
 * `tool.dismiss_popup`（docs/design/flows-recording-recovery.md R6）：关闭遮挡当前页面的弹窗。
 * 只点击规则允许的关闭按钮；找不到返回 `device.popup_not_found`。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.GestureController
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.popup.PopupFinder
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
import com.example.autop.device.impl.popup.PopupRulesLoader
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** prepare 生成的执行载荷：要点击的屏幕像素坐标与依据。 */
private data class PopupAction(
    val x: Int,
    val y: Int,
    val reason: String,
)

/** 关闭弹窗工具。 */
@Singleton
public class DismissPopupTool
    @Inject
    constructor(
        private val screenReader: ScreenReader,
        private val gestures: GestureController,
        private val subjects: PolicySubjectBuilder,
        private val epilogue: ActionEpilogue,
        private val rules: PopupRulesLoader,
    ) : ScreenActionTool {
        override val id: String = ID

        override val spec = DeviceToolSpecs.dismissPopup

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> =
            // N193 空树降级：空树时 PopupFinder 找不到弹窗 → popup_not_found，
            // 而不是被节点树缺失卡成 service_unavailable。
            when (val got = screenReader.snapshotOrDegraded()) {
                is Outcome.Err -> got
                is Outcome.Ok -> prepareWith(got.value)
            }

        private fun prepareWith(snap: ScreenSnapshot): Outcome<PreparedAction> {
            val pick =
                PopupFinder.find(snap, rules.get())
                    ?: return Outcome.Err(
                        AppError(
                            code = DeviceErrorCodes.POPUP_NOT_FOUND,
                            message = "没有发现可关闭的弹窗",
                            hint = "请检查屏幕；如果确实需要返回，用 global_action back",
                        ),
                    )
            val center = pick.node.bounds
            val action = PopupAction(x = (center.l + center.r) / 2, y = (center.t + center.b) / 2, reason = pick.reason)
            val extra = mapOf(EXTRA_REASON to pick.reason)
            return Outcome.Ok(PreparedAction(subjects.build(ID, snap, null, extra), action))
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val popup = action.payload as PopupAction
            val pre = epilogue.begin(ctx)
            if (!gestures.tap(popup.x, popup.y)) {
                return toolError(DeviceErrorCodes.GESTURE_CANCELLED, "关闭弹窗的点击被取消")
            }
            // 手势坐标点击：回放记录标 execution=gesture 并带实际落点（docs/tech/05 §13）。
            return epilogue.finish(
                ctx,
                "已关闭弹窗（依据：${popup.reason}）",
                null,
                ReplayInput(ID, pre, JsonObject(emptyMap()), gesture = gestureJson(popup.x, popup.y, TAP_DURATION_MS)),
            )
        }

        private companion object {
            const val ID = "tool.dismiss_popup"
            const val EXTRA_REASON = "popupReason"
            const val TAP_DURATION_MS = 0L
        }
    }
