/**
 * 圆弧菜单窗口装配（docs/tech/08 §5.1，N182）：点球展开/收起圆弧菜单、动作分发。
 * 窗口 `register` 由 app 组装层调用 `OverlayWindowManager`（FEATURE 不依赖 IMPL，docs/tech/01 §4），
 * 本类只通过 [OverlayHost] 端口操作窗口。
 */
package com.example.autop.feature.overlay.ball

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.onErr
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.ui.overlay.OverlayLifecycleOwner
import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlayHost
import com.example.autop.device.OverlaySpec
import com.example.autop.feature.overlay.OverlayWindowIds
import com.example.autop.feature.overlay.composeOverlayView
import com.example.autop.feature.overlay.panel.PanelController
import com.example.autop.feature.overlay.panel.PanelRunActions
import com.example.autop.feature.overlay.probe.ProbeController
import com.example.autop.feature.overlay.progress.ProgressController
import com.example.autop.feature.overlay.record.RecordController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** 圆弧菜单窗口的注册与动作分发（进程级）。 */
@Singleton
@Suppress("LongParameterList") // 装配类：端口 + 动作 + 面板/进度/工具测试控制，不可再减（questions.md N114 口径）
public class BallMenuController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val host: OverlayHost,
        private val holder: BallStateHolder,
        private val actions: PanelRunActions,
        private val panelController: PanelController,
        private val progressController: ProgressController,
        private val probeController: ProbeController,
        private val recordController: RecordController,
        private val configEditor: ConfigEditor,
        private val logger: Logger,
        dispatchers: DispatcherProvider,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
        private val openState = MutableStateFlow(false)

        /** 菜单窗口内容工厂（app 组装层注册到 `OverlayWindowManager`）。 */
        public fun menuFactory(): (Context, OverlayLifecycleOwner) -> View =
            { windowContext, owner ->
                composeOverlayView(windowContext, owner) { MenuContent() }
            }

        /** 球单击：展开或收起圆弧菜单（同点收起，docs/tech/08 §5.1）。 */
        public fun toggle() {
            if (openState.value) {
                dismiss()
            } else {
                show()
            }
        }

        /** 展开圆弧菜单（整屏透明层，点空白收起）。 */
        public fun show() {
            openState.value = true
            host.show(OverlayWindowIds.MENU, menuSpec(context))
        }

        /** 收起圆弧菜单。 */
        public fun dismiss() {
            if (!openState.value) return
            openState.value = false
            host.hide(OverlayWindowIds.MENU)
        }

        /** 菜单内容：按钮集合随活跃 Run 条件变化，位置环绕球心。 */
        @Composable
        private fun MenuContent() {
            val active by actions.active.collectAsState()
            val position by holder.position.collectAsState()
            val density = LocalDensity.current
            val windowSize = LocalWindowInfo.current.containerSize
            val ballSizePx = with(density) { BALL_SIZE.toPx() }
            val centerX =
                if (position.x == BallStateHolder.UNSET) {
                    windowSize.width / 2f
                } else {
                    position.x + ballSizePx / 2
                }
            val centerY =
                if (position.y == BallStateHolder.UNSET) {
                    windowSize.height / DEFAULT_Y_FRACTION
                } else {
                    position.y + ballSizePx / 2
                }
            val callbacks =
                remember(this) {
                    BallMenuCallbacks(onAction = ::onAction, onDismiss = ::dismiss)
                }
            BallMenuScreen(
                actions = deriveBallMenu(active),
                ballCenterX = centerX,
                ballCenterY = centerY,
                callbacks = callbacks,
            )
        }

        /** 动作分发（先收起菜单再执行，N182）。 */
        private fun onAction(action: BallMenuAction) {
            dismiss()
            when (action) {
                BallMenuAction.CREATE_TASK -> panelController.showInput(screenWidthPx())
                BallMenuAction.OPEN_APP -> actions.openApp()
                BallMenuAction.VIEW_PROGRESS -> progressController.show()
                BallMenuAction.ABORT_RUN -> actions.stop()
                BallMenuAction.RECORD -> recordController.show()
                BallMenuAction.TOOL_TEST -> probeController.show()
                BallMenuAction.HIDE_BALL -> hideBall()
            }
        }

        /** 隐藏悬浮球：写 `ui.overlay.enabled=false`（配置观察流即时收球，设置页可再开）。 */
        private fun hideBall() {
            scope.launch {
                configEditor.setOverride(OVERLAY_ENABLED_PATH, JsonPrimitive(false)).onErr { error ->
                    logger.e(
                        tag = TAG,
                        event = "hide_ball_failed",
                        "code" to error.code,
                        error = error.cause,
                    )
                }
            }
        }

        private fun screenWidthPx(): Int = context.resources.displayMetrics.widthPixels

        private companion object {
            /** 未摆放时菜单默认高度：屏高 / 3。 */
            const val DEFAULT_Y_FRACTION: Float = 3f

            /** 悬浮球显隐写入路径（ui.overlay.enabled）。 */
            const val OVERLAY_ENABLED_PATH = "ui.overlay.enabled"

            const val TAG = "BallMenu"
        }
    }

/** 菜单窗口 spec（N182）：整屏透明、可触摸不可聚焦（不需要输入法）。
 *  fullScreen=true：与球窗同坐标系（物理屏幕），球心/按钮摆位不受系统栏 inset 影响。 */
internal fun menuSpec(context: Context): OverlaySpec {
    val metrics = context.resources.displayMetrics
    return OverlaySpec(
        x = 0,
        y = 0,
        width = metrics.widthPixels,
        height = metrics.heightPixels,
        touchable = true,
        focusable = false,
        gravity = OverlayGravity.TOP_LEFT,
        fullScreen = true,
    )
}
