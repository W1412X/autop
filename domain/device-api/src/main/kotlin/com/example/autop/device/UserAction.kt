/**
 * 用户操作采集端口（docs/design/flows-recording-recovery.md V2）：录制流程时，把用户在目标 App 上的操作
 * 转成结构化记录。只在录制会话期间工作；不记录密码框内容。
 */
package com.example.autop.device

import kotlinx.coroutines.flow.SharedFlow

/** 用户操作类型。 */
public enum class UserActionType {
    CLICK,
    LONG_CLICK,
    TEXT_CHANGED,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    WINDOW_CHANGED,

    /** 拾取模式添加的“等待该元素出现”步骤（不来自无障碍事件）。 */
    WAIT_ELEMENT,
}

/** 一次用户操作（节点级信息，没有屏幕坐标）。 */
public data class UserAction(
    val type: UserActionType,
    val packageName: String?,
    val className: String?,
    val viewId: String?,
    /** 节点文字；密码框始终为 null。 */
    val text: String?,
    val contentDescription: String?,
    /** 节点在屏幕上的像素边界。 */
    val bounds: Rect?,
    /** 是否来自密码框（文字已被丢弃）。 */
    val isPassword: Boolean,
    /** 事件时间（毫秒）。 */
    val time: Long,
)

/** 用户操作采集。 */
public interface UserActionMonitor {
    /** 录制期间产生的操作。 */
    public val actions: SharedFlow<UserAction>

    /** 开始采集；重复调用无效。 */
    public fun start()

    /** 停止采集并释放资源。 */
    public fun stop()
}
