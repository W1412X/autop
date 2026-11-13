/**
 * task 域错误码（docs/tech/07 §1.4）。
 */
package com.example.autop.task

public object TaskErrorCodes {
    public const val INVALID: String = "task.invalid"
    public const val NOT_FOUND: String = "task.not_found"

    /** 继续执行时源会话没有可分叉的事件点（ADR-0021）。 */
    public const val NO_FORK_POINT: String = "task.no_fork_point"
    public const val CRON_INVALID: String = "task.cron_invalid"
    public const val SOURCE_UNKNOWN: String = "task.source_unknown"
    public const val PERMISSION_MISSING: String = "task.permission_missing"
    public const val DAILY_LIMIT: String = "task.daily_limit"
    public const val COOLDOWN: String = "task.cooldown"
    public const val SCREEN_BUSY: String = "task.screen_busy"
    public const val LOCKED: String = "task.locked"
    public const val EXPIRED: String = "task.expired"
    public const val LOST_ON_RESTART: String = "task.lost_on_restart"

    /** 通知权限未授予，通知不会显示（notify_user）。 */
    public const val NOTIFICATION_DENIED: String = "task.notification_denied"

    /** 流程 Beta 功能关闭（docs/adr/0030-beta-feature-switch.md）：不能绑定或运行流程。 */
    public const val FLOW_DISABLED: String = "task.flow_disabled"
}
