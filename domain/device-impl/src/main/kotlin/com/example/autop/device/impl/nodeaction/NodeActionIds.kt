/**
 * 无障碍动作 id 与方向映射（coordinate-node-action-split：原 NodeActionRouter 的公共部分，
 * 节点工具（tap_node/scroll_node）与滚动几何共用）。
 */
package com.example.autop.device.impl.nodeaction

import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.example.autop.device.core.ScrollDirection

/** 无障碍动作 id 与方向映射。 */
internal object NodeActionIds {
    val CLICK: Int = AccessibilityAction.ACTION_CLICK.id
    val LONG_CLICK: Int = AccessibilityAction.ACTION_LONG_CLICK.id

    /**
     * 手指方向 → 滚动动作。`up`（手指上滑，看下方内容）对应“向下滚动内容”动作，其余同理。
     * 与 RecyclerView/ScrollView 的无障碍实现一致，真机验证项见 ADR-0015。
     */
    fun scrollActionOf(direction: ScrollDirection): Int =
        when (direction) {
            ScrollDirection.UP -> AccessibilityAction.ACTION_SCROLL_DOWN.id
            ScrollDirection.DOWN -> AccessibilityAction.ACTION_SCROLL_UP.id
            ScrollDirection.LEFT -> AccessibilityAction.ACTION_SCROLL_RIGHT.id
            ScrollDirection.RIGHT -> AccessibilityAction.ACTION_SCROLL_LEFT.id
        }

    /** 滚动动作名（回执展示用）。 */
    fun scrollLabelOf(direction: ScrollDirection): String =
        when (direction) {
            ScrollDirection.UP -> "ACTION_SCROLL_DOWN"
            ScrollDirection.DOWN -> "ACTION_SCROLL_UP"
            ScrollDirection.LEFT -> "ACTION_SCROLL_RIGHT"
            ScrollDirection.RIGHT -> "ACTION_SCROLL_LEFT"
        }
}
