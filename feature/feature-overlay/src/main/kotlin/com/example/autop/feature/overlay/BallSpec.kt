/**
 * 悬浮球窗口 spec（docs/tech/08 §5.1）：球 52dp＋光晕外扩、可触摸不可聚焦、按球位置摆放。
 * N217：窗口比球每侧大出 [HALO_PAD]（呼吸光晕画在球外需要余量，见 [BALL_WINDOW_SIZE]），
 * 窗口以球为中心，x/y 为球左上角减去外扩。
 */
package com.example.autop.feature.overlay

import android.content.Context
import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlaySpec
import com.example.autop.feature.overlay.ball.BALL_SIZE
import com.example.autop.feature.overlay.ball.BALL_WINDOW_SIZE
import com.example.autop.feature.overlay.ball.BallPosition
import com.example.autop.feature.overlay.ball.HALO_PAD
import kotlin.math.roundToInt

/** 球边长（px）：拖动钳制、吸附与唤出的基准尺寸。 */
internal fun ballSizePx(context: Context): Int =
    (BALL_SIZE.value * context.resources.displayMetrics.density).roundToInt()

/** 球窗口 spec：位置为球左上角偏移外扩后的窗口左上角（px，相对 gravity TOP_LEFT）。 */
internal fun ballSpec(
    context: Context,
    position: BallPosition,
): OverlaySpec {
    val density = context.resources.displayMetrics.density
    val pad = (HALO_PAD.value * density).roundToInt()
    val size = (BALL_WINDOW_SIZE.value * density).roundToInt()
    return OverlaySpec(
        x = position.x - pad,
        y = position.y - pad,
        width = size,
        height = size,
        touchable = true,
        focusable = false,
        gravity = OverlayGravity.TOP_LEFT,
    )
}
