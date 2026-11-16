/**
 * 时间线的宿主差异（ADR-0039）：只放布局相关的参数。行为差异（长按菜单、分叉、链接跳转）
 * 由宿主在 [TimelineAction] 回调里决定，不放在这里。
 */
package com.example.autop.core.thread.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp

@Immutable
public data class TimelineStyle(
    /**
     * 消息流的可用宽度。用户气泡按它的一定比例限宽；为 null 时按屏幕宽度。
     * 对话页不传（全屏）；进度悬浮窗传入窗口内容宽度，避免气泡超出窗口。
     */
    public val availableWidth: Dp? = null,
)
