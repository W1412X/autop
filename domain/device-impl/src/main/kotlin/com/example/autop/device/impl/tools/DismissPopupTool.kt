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
import com.example.autop.device.InjectionChannel
import com.example.autop.device.Point
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.popup.PopupFinder
import com.example.autop.device.core.popup.PopupRules
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.nodeaction.NodeActionIds
import com.example.autop.device.impl.nodeaction.NodeActionRouter
import com.example.autop.device.impl.nodeaction.NodeKind
import com.example.autop.device.impl.nodeaction.NodeRoute
import com.example.autop.device.impl.nodeaction.NodeRouteRequest
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
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
        private val router: NodeActionRouter,
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
                            hint = if (snap.nodes.isEmpty()) NO_TREE_HINT else NO_POPUP_HINT,
                        ),
                    )
            val center = pick.node.bounds
            val action =
                PopupAction(
                    snapshot = snap,
                    x = (center.l + center.r) / 2,
                    y = (center.t + center.b) / 2,
                    reason = pick.reason,
                )
            val extra = mapOf(EXTRA_REASON to pick.reason)
            return Outcome.Ok(PreparedAction(subjects.build(ID, snap, null, extra), action))
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val popup = action.payload as PopupAction
            val pre = epilogue.begin(ctx)
            val route =
                router.attempt(
                    NodeRouteRequest(
                        ctx = ctx,
                        snapshot = popup.snapshot,
                        point = Point(popup.x, popup.y),
                        kind = NodeKind.CLICK,
                        actionId = NodeActionIds.CLICK,
                    ),
                )
            val injected =
                if (route is NodeRoute.Done) {
                    null
                } else {
                    val result = gestures.tap(popup.x, popup.y)
                    if (!result.ok) return errorOutput(injectFailed("关闭弹窗的点击"))
                    result
                }
            val execution =
                if (route is NodeRoute.Done) {
                    executionLine(ExecMethod.NODE_ACTION, ACTION_CLICK)
                } else {
                    executionLine(
                        ExecMethod.GESTURE,
                        channel = injected?.channel ?: InjectionChannel.NONE,
                        fallback = routeFallback(route, ACTION_CLICK),
                    )
                }
            // 手势坐标点击：回放记录标 execution=gesture 并带实际落点（docs/tech/05 §13）。
            val out =
                epilogue.finish(
                    ctx,
                    "已点击弹窗关闭按钮（依据：${popup.reason}）",
                    null,
                    ReplayInput(
                        ID,
                        pre,
                        JsonObject(emptyMap()),
                        gesture = if (injected != null) gestureJson(popup.x, popup.y, TAP_DURATION_MS) else null,
                    ),
                )
            return out.copy(
                content =
                    out.content +
                        listOf(
                            Block.Text(execution),
                            Block.Text(verifyClosed()),
                        ),
            )
        }

        /** 动作后重新找一次弹窗：消失/仍在/无法确认，不把“点了按钮”说成“弹窗已关闭”。 */
        private suspend fun verifyClosed(): String =
            when (popupStateOf(screenReader.snapshot(), rules.get())) {
                PopupState.CLOSED -> VERIFY_CLOSED
                PopupState.STILL_THERE -> VERIFY_STILL_THERE
                PopupState.UNKNOWN -> VERIFY_UNKNOWN
            }

        private companion object {
            const val ID = "tool.dismiss_popup"
            const val EXTRA_REASON = "popupReason"
            const val ACTION_CLICK = "ACTION_CLICK"
            const val TAP_DURATION_MS = 0L
            const val NO_TREE_HINT =
                "当前没有可用的无障碍节点树（应用可能屏蔽了节点）；dismiss_popup 依赖节点树，" +
                    "请先 observe 视觉或 screenshot 找到关闭按钮后按坐标 tap，或先 observe a11y 再试。"
            const val NO_POPUP_HINT = "请检查屏幕；如果确实需要返回，用 global_action back"
            const val VERIFY_CLOSED = "验证：已不再发现可关闭的弹窗"
            const val VERIFY_STILL_THERE =
                "验证：仍发现可关闭的弹窗，可能没有关闭；不要重复点击同一位置，先 observe 确认界面。"
            const val VERIFY_UNKNOWN =
                "验证：无法确认弹窗是否关闭（没有可用的无障碍节点树）；请用 observe 或 screenshot 确认。"
        }
    }

/** 关闭弹窗后的校验结果：只有拿到完整节点树且确实找不到弹窗，才敢说已关闭。 */
internal enum class PopupState {
    CLOSED,
    STILL_THERE,
    UNKNOWN,
}

/** 用动作后的快照判断弹窗状态；快照失败或空树（应用屏蔽节点）时无法确认。 */
internal fun popupStateOf(
    post: Outcome<ScreenSnapshot>,
    rules: PopupRules,
): PopupState =
    when (post) {
        is Outcome.Err -> PopupState.UNKNOWN
        is Outcome.Ok ->
            when {
                post.value.nodes.isEmpty() -> PopupState.UNKNOWN
                PopupFinder.find(post.value, rules) == null -> PopupState.CLOSED
                else -> PopupState.STILL_THERE
            }
    }
