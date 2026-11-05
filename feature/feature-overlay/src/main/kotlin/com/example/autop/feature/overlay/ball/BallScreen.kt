/**
 * 悬浮球交互层（docs/tech/08 §5.1）：拖动跟手、松手吸附、单击/双击分发。
 * 球窗口位置由 WindowManager 移动（回调 [BallCallbacks.onMove]），本层只渲染与手势。
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 球窗口视口（位置与尺寸，手势层用）。 */
private data class BallViewport(
    val position: BallPosition,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val ballSizePx: Int,
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
    val viewport =
        BallViewport(
            position = position,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            ballSizePx = ballSizePx,
        )
    // N192：尺寸变化（旋转/折叠）时重新钳制摆放，避免球留在旧屏幕坐标外。
    LaunchedEffect(screenWidthPx, screenHeightPx) {
        callbacks.onFirstLayout(viewport.screenWidthPx, viewport.screenHeightPx, ballSizePx)
    }
    // 无障碍（N185）：状态变化时描述同步变化（不只靠颜色区分），双击/点按动作暴露给读屏。
    val description = stringResource(ballDescriptionOf(uiState.mode))
    Box(
        modifier =
            modifier
                .semantics {
                    contentDescription = description
                    onClick {
                        callbacks.onTap(viewport.screenWidthPx)
                        true
                    }
                }.ballGestures(uiState, callbacks, viewport),
    ) {
        BallVisual(uiState)
    }
}

/**
 * 球手势（N185 抽出）：点按（圆弧菜单 / 无障碍设置）与双击（停止）分发，
 * 拖动跟手移动、松手吸附到最近左右边（[BallCallbacks.onDragEnd]）。
 *
 * N217：手势闭包里引用的状态必须经 [rememberUpdatedState] 取当帧值——
 * `pointerInput(Unit)` 的 lambda 不随重组重启，直接捕获首帧 [BallViewport] 会让
 * 拖动起点永远停在初始位置（按下瞬间球闪现到别处，之后才跟手）。
 */
@Composable
private fun Modifier.ballGestures(
    uiState: BallUiState,
    callbacks: BallCallbacks,
    viewport: BallViewport,
): Modifier {
    val state by rememberUpdatedState(uiState)
    val current by rememberUpdatedState(viewport)
    return this
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = {
                    if (state.mode is BallMode.AccessibilityLost) {
                        callbacks.onAccessibilityTap()
                    } else {
                        callbacks.onTap(current.screenWidthPx)
                    }
                },
                onDoubleTap = {
                    if (state.doubleTapStops && state.mode is BallMode.Running) {
                        callbacks.onDoubleTap()
                    }
                },
            )
        }.pointerInput(Unit) {
            var dragOrigin = Offset.Zero
            var dragTotal = Offset.Zero
            detectDragGestures(
                onDragStart = {
                    dragOrigin = Offset(current.position.x.toFloat(), current.position.y.toFloat())
                    dragTotal = Offset.Zero
                },
                onDrag = { change, amount ->
                    change.consume()
                    dragTotal += amount
                    val hide =
                        (current.ballSizePx * state.edgeHideRatio.coerceIn(MIN_HIDE_RATIO, MAX_HIDE_RATIO))
                            .roundToInt()
                    val x =
                        (dragOrigin.x + dragTotal.x)
                            .roundToInt()
                            .coerceIn(-hide, current.screenWidthPx - current.ballSizePx + hide)
                    val y =
                        (dragOrigin.y + dragTotal.y)
                            .roundToInt()
                            .coerceIn(0, current.screenHeightPx - current.ballSizePx)
                    callbacks.onMove(x, y)
                },
                onDragEnd = {
                    callbacks.onDragEnd(current.ballSizePx, current.screenWidthPx, state.edgeHideRatio)
                },
            )
        }
}
