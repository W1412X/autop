/**
 * 屏幕元素拾取（flows-recording-recovery.md V5 拾取模式）：按屏幕像素点查找该位置上的界面元素。
 */
package com.example.autop.device

import com.example.autop.core.common.Outcome

/** 拾取到的元素（只含定位所需信息）。 */
public data class PickedElement(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    /** 屏幕像素边界。 */
    val bounds: Rect,
)

/** 元素拾取端口。 */
public interface ElementPicker {
    /** 查找点 (x, y)（屏幕像素）上最小的、带文字或描述或可点击的元素。 */
    public suspend fun pickAt(
        x: Int,
        y: Int,
    ): Outcome<PickedElement>
}
