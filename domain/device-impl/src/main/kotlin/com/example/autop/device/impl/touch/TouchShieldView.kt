/**
 * 触摸盾视图（docs/tech/05 §8，N186）：全屏透明、吞掉一切落在窗口内的用户触摸
 * （点击/滑动/长按都不穿透到底层应用），工具注入手势由控制器在动作期间放行窗口。
 *
 * 无障碍洁净（用户要求：工具读取界面元素必须拿到真实界面）：
 * - `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`——整棵子树不进无障碍节点树，
 *   快照采集（SnapshotCapturer）与定位解析看不到盾；
 * - 不设 clickable——即便节点被采集到也无 `CLICKABLE` 标志，不会被 `TapPointResolver`
 *   当成全屏遮挡者；
 * - 序列化侧另有既有过滤兜底（`ScreenSerializer` 排除 `ACCESSIBILITY_OVERLAY` 与
 *   自家包名窗口）。
 */
package com.example.autop.device.impl.touch

import android.content.Context
import android.view.MotionEvent
import android.view.View

/** 触摸盾 View：只吞事件，不绘制任何内容（截图上不可见）。 */
internal class TouchShieldView(
    context: Context,
) : View(context) {
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = true
}
