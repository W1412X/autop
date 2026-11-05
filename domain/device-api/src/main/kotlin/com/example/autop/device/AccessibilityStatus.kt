/**
 * 无障碍服务在线状态（docs/tech/05 §1.3）：首页提示、引导页与调度器使用。
 */
package com.example.autop.device

import kotlinx.coroutines.flow.StateFlow

/** 无障碍服务连接状态。 */
public interface AccessibilityStatus {
    /** 是否已连接。 */
    public val connected: StateFlow<Boolean>
}
