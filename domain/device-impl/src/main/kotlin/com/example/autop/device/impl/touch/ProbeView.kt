/**
 * 触摸探针视图（docs/tech/05 §8）：1×1 透明窗口内容，收到 `ACTION_OUTSIDE`
 * 即回调触摸点坐标（rawX/rawY 屏幕坐标）——任何屏幕触摸都会落在这个 1×1 窗口之外。
 */
package com.example.autop.device.impl.touch

import android.content.Context
import android.view.MotionEvent
import android.view.View

/** 探针 View：只观察，不消费；回调携带触摸点坐标（N139 自家窗口排除用）。 */
internal class ProbeView(
    context: Context,
    private val onOutsideTouch: (x: Float, y: Float) -> Unit,
) : View(context) {
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutsideTouch(event.rawX, event.rawY)
        return false
    }
}
