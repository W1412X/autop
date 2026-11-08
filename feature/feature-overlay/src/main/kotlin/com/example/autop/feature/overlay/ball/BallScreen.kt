/**
 * 悬浮球交互层（docs/tech/08 §5.1）：拖动跟手、松手磁吸/就地、单击/双击分发。
 * 球窗口位置由 WindowManager 移动（回调 [BallCallbacks.onMove]），本层只渲染与手势。
 *
 * 停靠形态（[BallDock]）：自由态整球；半贴边态弱化缩小；隐身态细条贴屏幕缘。
 */
package com.example.autop.feature.overlay.ball

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 松手磁吸判定距离（球心距边缘，dp）。 */
private val MAGNET_SIZE = 28.dp

/** 半贴边态的弱化透明度（贴边时球与屏幕缘齐平，不做缩放——缩放会以中心为锚内缩留缝）。 */
private const val DOCK_ALPHA = 0.6f

/** 球窗口视口（位置与尺寸，手势层用）。 */
private data class BallViewport(
    val position: BallPosition,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val ballSizePx: Int,
    val sliverPx: Int,
    val magnetPx: Int,
)

/** 悬浮球：视觉 + 手势。 */
@Composable
internal fun BallScreen(
    uiState: BallUiState,
    position: BallPosition,
    callbacks: BallCallbacks,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // N192：屏幕尺寸取真实 Display 尺寸（LocalConfiguration 随旋转重组，横竖屏都正确）；
    // 不能用 LocalWindowInfo.containerSize——那是球窗口自身尺寸（52dp），
    // 拖动钳制会把球钉在原地附近，拖不到屏幕边缘。
    val config = LocalConfiguration.current
    val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx() }.roundToInt()
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }.roundToInt()
    val ballSizePx = with(density) { BALL_SIZE.toPx() }.roundToInt()
    val sliverPx = with(density) { SLIVER_WIDTH.toPx() }.roundToInt()
    val magnetPx = with(density) { MAGNET_SIZE.toPx() }.roundToInt()
    val viewport =
        BallViewport(
            position = position,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            ballSizePx = ballSizePx,
            sliverPx = sliverPx,
            magnetPx = magnetPx,
        )
    // N192：尺寸变化（旋转/折叠）时重新钳制摆放，避免球留在旧屏幕坐标外。
    LaunchedEffect(screenWidthPx, screenHeightPx) {
        callbacks.onFirstLayout(viewport.screenWidthPx, viewport.screenHeightPx, ballSizePx, sliverPx)
    }
    // 单击语义（N185）：细条/半贴边唤出、自由态开菜单、无障碍断开跳设置（读屏与手势同径）。
    val state by rememberUpdatedState(uiState)
    val current by rememberUpdatedState(viewport)
    val tap: () -> Unit = {
        when {
            state.mode is BallMode.AccessibilityLost -> callbacks.onAccessibilityTap()
            current.position.dock != BallDock.NONE -> callbacks.onReveal(current.screenWidthPx, current.ballSizePx)
            else -> callbacks.onTap(current.screenWidthPx)
        }
    }
    val description = stringResource(ballDescriptionOf(uiState.mode))
    Box(
        modifier =
            modifier
                .semantics {
                    contentDescription = description
                    onClick {
                        tap()
                        true
                    }
                }.ballGestures(tap, callbacks, current),
    ) {
        when (position.dock) {
            BallDock.SLIVER -> BallSliver(uiState, position.side)
            BallDock.EDGE -> BallVisual(uiState, Modifier.graphicsLayer { alpha = DOCK_ALPHA })
            BallDock.NONE -> BallVisual(uiState)
        }
    }
}

/**
 * 球手势：点按（唤出 / 圆弧菜单 / 无障碍设置）与双击（停止）分发，
 * 拖动跟手移动、松手磁吸或就地停留（[BallCallbacks.onDragEnd]）。
 *
 * N217：手势闭包里引用的状态必须经 [rememberUpdatedState] 取当帧值——
 * `pointerInput(Unit)` 的 lambda 不随重组重启，直接捕获首帧状态会让
 * 拖动起点永远停在初始位置（按下瞬间球闪现到别处，之后才跟手）。
 */
@Composable
private fun Modifier.ballGestures(
    tap: () -> Unit,
    callbacks: BallCallbacks,
    viewport: BallViewport,
): Modifier {
    val state by rememberUpdatedState(viewport)
    val onTap by rememberUpdatedState(tap)
    return this
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = {
                    if (state.position.dock == BallDock.NONE) callbacks.onDoubleTap()
                },
            )
        }.pointerInput(Unit) {
            var dragOrigin = Offset.Zero
            var dragTotal = Offset.Zero
            detectDragGestures(
                onDragStart = {
                    dragOrigin = Offset(state.position.x.toFloat(), state.position.y.toFloat())
                    dragTotal = Offset.Zero
                },
                onDrag = { change, amount ->
                    change.consume()
                    dragTotal += amount
                    val visible = (state.ballSizePx * DOCK_VISIBLE_RATIO).roundToInt()
                    val x =
                        (dragOrigin.x + dragTotal.x)
                            .roundToInt()
                            .coerceIn(visible - state.ballSizePx, state.screenWidthPx - visible)
                    val y =
                        (dragOrigin.y + dragTotal.y)
                            .roundToInt()
                            .coerceIn(0, state.screenHeightPx - state.ballSizePx)
                    callbacks.onMove(x, y)
                },
                onDragEnd = {
                    callbacks.onDragEnd(
                        state.ballSizePx,
                        state.screenWidthPx,
                        state.sliverPx,
                        state.magnetPx,
                    )
                },
            )
        }
}
