/**
 * 工具测试面板窗口 spec（N198）：`overlayPanelWidth` 宽、屏高约六成、靠右边缘垂直
 * 居中（把屏幕左侧留给被测应用）；可聚焦（参数 JSON 编辑框需要输入法）。
 */
package com.example.autop.feature.overlay.probe

import android.content.Context
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlaySpec
import kotlin.math.roundToInt

/** 测试面板高度占屏比例。 */
private const val HEIGHT_FRACTION: Float = 0.6f

/** 面板贴右边缘的间距（dp）。 */
private const val EDGE_MARGIN_DP: Float = 8f

/** 工具测试面板 spec：位置为窗口左上角（px，相对 gravity TOP_LEFT）。 */
internal fun probeSpec(context: Context): OverlaySpec {
    val metrics = context.resources.displayMetrics
    val density = metrics.density
    val width = (AutopDimens.overlayPanelWidth.value * density).roundToInt()
    val height = (metrics.heightPixels * HEIGHT_FRACTION).roundToInt()
    val margin = (EDGE_MARGIN_DP * density).roundToInt()
    return OverlaySpec(
        x = metrics.widthPixels - width - margin,
        y = (metrics.heightPixels - height) / 2,
        width = width,
        height = height,
        touchable = true,
        focusable = true,
        gravity = OverlayGravity.TOP_LEFT,
        watchOutsideTouch = false,
    )
}
