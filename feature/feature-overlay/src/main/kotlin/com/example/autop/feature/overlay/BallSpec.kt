/**
 * 悬浮球窗口 spec（docs/tech/08 §5.1）：球直径来自 `ui.overlay.ballSizeDp`（ADR-0046），
 * 窗口比球每侧大出余量（呼吸光晕与卫星画在球外需要空间，见 [BallDims.haloPad]），
 * 窗口以球为中心，x/y 为球左上角减去余量。
 * fullScreen=true（N22x）：默认悬浮窗被摆在系统栏之内、出屏坐标被钳回，贴边/细条形态
 * 的负坐标落不下去（球整体被推进屏内、离屏幕缘一大截）——必须拿物理屏幕坐标系
 * （与触摸盾同口径，见 [com.example.autop.device.OverlaySpec.fullScreen] 说明）。
 */
package com.example.autop.feature.overlay

import android.content.Context
import androidx.compose.ui.unit.dp
import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlaySpec
import com.example.autop.feature.overlay.ball.BallDims
import com.example.autop.feature.overlay.ball.BallPosition
import kotlin.math.roundToInt

/** 球窗口 spec：位置为球左上角偏移余量后的窗口左上角（px，相对 gravity TOP_LEFT）。 */
internal fun ballSpec(
    context: Context,
    position: BallPosition,
    ballSizeDp: Int,
): OverlaySpec {
    val density = context.resources.displayMetrics.density
    val dims = BallDims(ballSizeDp.dp)
    val pad = (dims.haloPad.value * density).roundToInt()
    val size = (dims.windowDp.value * density).roundToInt()
    return OverlaySpec(
        x = position.x - pad,
        y = position.y - pad,
        width = size,
        height = size,
        touchable = true,
        focusable = false,
        gravity = OverlayGravity.TOP_LEFT,
        fullScreen = true,
    )
}
