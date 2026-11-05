/**
 * 进度悬浮窗 spec（docs/tech/08 §5.3，N182）：`overlayPanelWidth` 宽、屏高约六成、靠球侧弹出、
 * 可聚焦（输入法供提问与补充指令）；外部点击**不**收起（可边看进度边操作其他应用，显式关闭）。
 */
package com.example.autop.feature.overlay.progress

import android.content.Context
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlaySpec
import com.example.autop.feature.overlay.ball.BallPosition
import com.example.autop.feature.overlay.ball.BallStateHolder
import com.example.autop.feature.overlay.panel.PANEL_GAP
import com.example.autop.feature.overlay.panel.panelXOf
import com.example.autop.feature.overlay.panel.panelYOf
import kotlin.math.roundToInt

/** 进度窗高度占屏比例。 */
private const val HEIGHT_FRACTION: Float = 0.55f

/** 未摆放球时默认位置的分母（屏高 / 3）。 */
private const val DEFAULT_Y_DIVISOR: Int = 3

/** 未摆放球时的右边距（dp）。 */
private const val FALLBACK_MARGIN_DP: Float = 16f

/** 进度窗 spec：位置为窗口左上角（px，相对 gravity TOP_LEFT）。 */
internal fun progressSpec(
    context: Context,
    ballPosition: BallPosition,
): OverlaySpec {
    val metrics = context.resources.displayMetrics
    val density = metrics.density
    val screenWidth = metrics.widthPixels
    val screenHeight = metrics.heightPixels
    val panelWidth = (AutopDimens.overlayPanelWidth.value * density).roundToInt()
    val ballSize = (AutopDimens.overlayBall.value * density).roundToInt()
    val gap = (PANEL_GAP.value * density).roundToInt()
    val height = (screenHeight * HEIGHT_FRACTION).roundToInt()
    val ballX =
        if (ballPosition.x == BallStateHolder.UNSET) {
            screenWidth - panelWidth - (FALLBACK_MARGIN_DP * density).roundToInt()
        } else {
            ballPosition.x
        }
    val ballY = if (ballPosition.y == BallStateHolder.UNSET) screenHeight / DEFAULT_Y_DIVISOR else ballPosition.y
    return OverlaySpec(
        x = panelXOf(ballX, ballSize, panelWidth, screenWidth, gap),
        y = panelYOf(ballY).coerceAtMost(screenHeight - height),
        width = panelWidth,
        height = height,
        touchable = true,
        focusable = true,
        gravity = OverlayGravity.TOP_LEFT,
        watchOutsideTouch = false,
    )
}
