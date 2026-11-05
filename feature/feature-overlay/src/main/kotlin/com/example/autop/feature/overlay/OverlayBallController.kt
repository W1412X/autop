/**
 * 悬浮球装配（docs/tech/08 §5.1）：注册窗口内容工厂、维护球窗口 spec、分发手势。
 * 窗口 `register` 由 app 组装层调用 `OverlayWindowManager`（FEATURE 不依赖 IMPL，docs/tech/01 §4），
 * 本类只通过 [OverlayHost] 端口操作窗口。
 */
package com.example.autop.feature.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.autop.core.ui.overlay.OverlayLifecycleOwner
import com.example.autop.device.OverlayHost
import com.example.autop.feature.overlay.ball.BallCallbacks
import com.example.autop.feature.overlay.ball.BallMenuController
import com.example.autop.feature.overlay.ball.BallPosition
import com.example.autop.feature.overlay.ball.BallPositionStore
import com.example.autop.feature.overlay.ball.BallScreen
import com.example.autop.feature.overlay.ball.BallStateHolder
import com.example.autop.task.RunService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 悬浮球窗口的注册与手势分发（进程级，状态在 [BallStateHolder]）。 */
@Singleton
@Suppress("LongParameterList") // 装配类：五个端口 + 圆弧菜单控制，不可再减（questions.md N114 口径）
public class OverlayBallController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val host: OverlayHost,
        private val runService: RunService,
        private val holder: BallStateHolder,
        private val positionStore: BallPositionStore,
        private val ballMenuController: BallMenuController,
    ) : BallCallbacks {
        /** 球窗口内容工厂（app 组装层注册到 `OverlayWindowManager`）。 */
        public fun ballFactory(): (Context, OverlayLifecycleOwner) -> View =
            { windowContext, owner ->
                composeOverlayView(windowContext, owner) { BallContent() }
            }

        /** 显示球窗口（通道可用时附着；位置取持久化值或原点，首帧摆放默认位置）。 */
        public fun start() {
            host.show(OverlayWindowIds.BALL, ballSpec(context, positionStore.load() ?: BallPosition(0, 0)))
        }

        /** 隐藏球窗口（`ui.overlay.enabled` 关闭时调用）。 */
        public fun hide() {
            host.hide(OverlayWindowIds.BALL)
        }

        override fun onMove(
            x: Int,
            y: Int,
        ) {
            holder.onMoved(x, y)
            host.update(OverlayWindowIds.BALL, ballSpec(context, BallPosition(x, y)))
        }

        override fun onDragEnd(
            ballSizePx: Int,
            screenWidthPx: Int,
            edgeHideRatio: Double,
        ) {
            holder.onSnap(ballSizePx, screenWidthPx, edgeHideRatio)
            host.update(OverlayWindowIds.BALL, ballSpec(context, holder.position.value))
        }

        override fun onFirstLayout(
            screenWidthPx: Int,
            screenHeightPx: Int,
            ballSizePx: Int,
        ) {
            holder.placeDefault(screenWidthPx, screenHeightPx, ballSizePx)
            host.update(OverlayWindowIds.BALL, ballSpec(context, holder.position.value))
        }

        /**
         * 单击（N217）：贴边收起态先全部唤出（露头点按展开），已唤出才展开/收起圆弧菜单（N182）。
         */
        override fun onTap(screenWidthPx: Int) {
            val position = holder.position.value
            if (position.hidden) {
                holder.onReveal(screenWidthPx, ballSizePx(context))
                host.update(OverlayWindowIds.BALL, ballSpec(context, holder.position.value))
            } else {
                ballMenuController.toggle()
            }
        }

        override fun onDoubleTap() {
            val active = runService.observeActive().value
            val runId = active?.run?.id
            if (runId != null) {
                runService.cancel(runId)
            }
        }

        override fun onAccessibilityTap() {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** 球窗口内容：把 [BallStateHolder] 的状态接到 [BallScreen]。 */
        @Composable
        private fun BallContent() {
            val uiState by holder.uiState.collectAsState()
            val position by holder.position.collectAsState()
            BallScreen(uiState, position, callbacks = this)
        }
    }
