package com.example.autop.core.common

/**
 * 系统权限/优化白名单状态查询端口（docs/tech/02 §4.1；WP11 引导与健康检查）。
 * SDK 差异封装在实现（core-platform）里，业务代码只问"能不能"。
 */
public interface PermissionChecks {
    /** 悬浮窗（显示在其他应用上层）是否已授予。 */
    public fun canDrawOverlay(): Boolean

    /** 通知运行时权限是否已授予（无此权限的平台返回 true）。 */
    public fun canPostNotifications(): Boolean

    /** 通知使用权（通知读取）是否已在系统授权名单；与监听服务连接状态无关（N190）。 */
    public fun canReadNotifications(): Boolean

    /** 全屏提醒是否可用（无此限制的平台返回 true）。 */
    public fun canUseFullScreenIntent(): Boolean

    /** 是否已在电池优化白名单（忽略电池优化）。 */
    public fun isIgnoringBatteryOptimizations(): Boolean
}
