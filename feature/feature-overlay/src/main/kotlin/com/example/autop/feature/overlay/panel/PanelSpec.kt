/**
 * 面板窗口 spec（docs/tech/08 §5.2）：`overlayPanelWidth` 宽、高度 wrap、靠球侧弹出、
 * 可聚焦（收起恢复）、`FLAG_WATCH_OUTSIDE_TOUCH` 捕获外部点击收起。
 */
package com.example.autop.feature.overlay.panel

import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlaySpec
import com.example.autop.feature.overlay.ball.BallPosition
import kotlin.math.roundToInt

/** 球与面板的间距。 */
internal val PANEL_GAP: Dp = 8.dp

/** 面板窗口 spec：位置为窗口左上角（px，相对 gravity TOP_LEFT）。 */
internal fun panelSpec(
    context: Context,
    ballPosition: BallPosition,
    screenWidthPx: Int,
): OverlaySpec {
    val density = context.resources.displayMetrics.density
    val panelWidth = (AutopDimens.overlayPanelWidth.value * density).roundToInt()
    val ballSize = (AutopDimens.overlayBall.value * density).roundToInt()
    val gap = (PANEL_GAP.value * density).roundToInt()
    return OverlaySpec(
        x = panelXOf(ballPosition.x, ballSize, panelWidth, screenWidthPx, gap),
        y = panelYOf(ballPosition.y),
        width = panelWidth,
        height = WindowManager.LayoutParams.WRAP_CONTENT,
        touchable = true,
        focusable = true,
        gravity = OverlayGravity.TOP_LEFT,
        watchOutsideTouch = true,
    )
}
