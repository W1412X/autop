/**
 * 面板窗口根布局（docs/tech/08 §5.2）：外部点击（`ACTION_OUTSIDE`）与返回键收起面板。
 */
package com.example.autop.feature.overlay.panel

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout

/** 根布局：只转发收起信号，不消费内容区手势。 */
internal class PanelRootView(
    context: Context,
    private val onOutsideTouch: () -> Unit,
    private val onBack: () -> Unit,
) : FrameLayout(context) {
    init {
        layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutsideTouch()
        return false
    }

    override fun onKeyPreIme(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onBack()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
