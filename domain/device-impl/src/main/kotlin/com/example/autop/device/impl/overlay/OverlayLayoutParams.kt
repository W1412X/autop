/**
 * `OverlaySpec` → `WindowManager.LayoutParams` 的映射（docs/tech/05 §10）。
 */
package com.example.autop.device.impl.overlay

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlaySpec

/**
 * 按通道与 spec 构建窗口参数；[hidden] 时窗口透明且不可触摸（截图隐藏），
 * [inputBlocked] 时不可触摸但保持可见（注入期间避免自家浮层吞掉注入动作）。
 */
internal fun buildOverlayParams(
    channel: OverlayChannel,
    spec: OverlaySpec,
    hidden: Boolean,
    inputBlocked: Boolean,
): WindowManager.LayoutParams {
    val type =
        when (channel) {
            OverlayChannel.ACCESSIBILITY -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            OverlayChannel.APPLICATION -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
    var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    if (!spec.focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    if (!spec.touchable || hidden || inputBlocked) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    if (spec.watchOutsideTouch) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    return WindowManager.LayoutParams(spec.width, spec.height, type, flags, PixelFormat.TRANSLUCENT).apply {
        gravity = gravityOf(spec.gravity)
        x = spec.x
        y = spec.y
        alpha = if (hidden) HIDDEN_ALPHA else VISIBLE_ALPHA
    }
}

/** 九宫格 + 居中 → Gravity 组合。 */
private fun gravityOf(gravity: OverlayGravity): Int =
    when (gravity) {
        OverlayGravity.TOP_LEFT -> Gravity.TOP or Gravity.START
        OverlayGravity.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        OverlayGravity.TOP_RIGHT -> Gravity.TOP or Gravity.END
        OverlayGravity.LEFT -> Gravity.CENTER_VERTICAL or Gravity.START
        OverlayGravity.CENTER -> Gravity.CENTER
        OverlayGravity.RIGHT -> Gravity.CENTER_VERTICAL or Gravity.END
        OverlayGravity.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
        OverlayGravity.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        OverlayGravity.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
    }

private const val HIDDEN_ALPHA: Float = 0f
private const val VISIBLE_ALPHA: Float = 1f
