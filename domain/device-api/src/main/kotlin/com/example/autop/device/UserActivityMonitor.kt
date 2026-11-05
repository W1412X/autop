/**
 * 用户触摸检测端口（docs/tech/05 §1.3、§8）：判断用户是否正在使用手机。
 */
package com.example.autop.device

import kotlinx.coroutines.flow.SharedFlow

/** 触摸检测（已排除自身动作窗口）。 */
public interface UserActivityMonitor {
    /** 用户触摸时间戳。 */
    public val userTouches: SharedFlow<Long>

    /** 最近一次用户触摸时间戳。 */
    public fun lastUserTouchAt(): Long?

    /** 标记自身动作的时间窗口，窗口内产生的触摸不视为用户操作。 */
    public fun markSelfActionWindow(
        startMs: Long,
        endMs: Long,
    )
}
