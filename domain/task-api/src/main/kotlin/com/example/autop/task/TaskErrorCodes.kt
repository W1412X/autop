/**
 * task 域错误码（docs/tech/07 §1.4）。
 */
package com.example.autop.task

public object TaskErrorCodes {
    public const val INVALID: String = "task.invalid"
    public const val NOT_FOUND: String = "task.not_found"
    public const val CRON_INVALID: String = "task.cron_invalid"
    public const val SOURCE_UNKNOWN: String = "task.source_unknown"
    public const val PERMISSION_MISSING: String = "task.permission_missing"
    public const val DAILY_LIMIT: String = "task.daily_limit"
    public const val COOLDOWN: String = "task.cooldown"
    public const val SCREEN_BUSY: String = "task.screen_busy"
    public const val LOCKED: String = "task.locked"
    public const val EXPIRED: String = "task.expired"
    public const val LOST_ON_RESTART: String = "task.lost_on_restart"
}
