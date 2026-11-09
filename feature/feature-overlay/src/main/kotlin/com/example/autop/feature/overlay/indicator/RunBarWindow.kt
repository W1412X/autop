/**
 * 运行控制条与详情面板的窗口 spec：控制条顶部窄条（宽随屏减边距、高 [RUN_BAR_HEIGHT]），
 * 详情面板在条下展开（宽同条、高约屏四成）。两者可触摸但窗口只包内容，条外触摸不被吞；
 * 尺寸固定为像素值（触摸探针按矩形排除自家窗口，<=0 视为不存在）。
 */
package com.example.autop.feature.overlay.indicator

import android.content.Context
import androidx.compose.ui.unit.dp
import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlaySpec
import kotlin.math.roundToInt

/** 控制条高度（dp）：与 [RunBarScreen] 内容高度一致，窗口高度须精确匹配。 */
internal val RUN_BAR_HEIGHT = 44.dp

/** 条的屏幕边距与条/详情面板间隙（dp）。 */
private val BAR_MARGIN = 8.dp

/** 详情面板高度占屏比例。 */
private const val DETAIL_HEIGHT_FRACTION: Float = 0.4f

/** 控制条窗口 spec：顶部窄条，可触摸、不可聚焦。 */
internal fun runBarSpec(context: Context): OverlaySpec {
    val metrics = context.resources.displayMetrics
    val margin = (BAR_MARGIN.value * metrics.density).roundToInt()
    return OverlaySpec(
        x = margin,
        y = 0,
        width = metrics.widthPixels - margin * 2,
        height = (RUN_BAR_HEIGHT.value * metrics.density).roundToInt(),
        touchable = true,
        focusable = false,
        gravity = OverlayGravity.TOP_LEFT,
    )
}

/** 详情面板窗口 spec：条下观测面板，可触摸（滚动消息流）、不可聚焦。 */
internal fun runDetailSpec(context: Context): OverlaySpec {
    val metrics = context.resources.displayMetrics
    val margin = (BAR_MARGIN.value * metrics.density).roundToInt()
    val gap = ((RUN_BAR_HEIGHT + BAR_MARGIN).value * metrics.density).roundToInt()
    return OverlaySpec(
        x = margin,
        y = gap,
        width = metrics.widthPixels - margin * 2,
        height = (metrics.heightPixels * DETAIL_HEIGHT_FRACTION).roundToInt(),
        touchable = true,
        focusable = false,
        gravity = OverlayGravity.TOP_LEFT,
    )
}
