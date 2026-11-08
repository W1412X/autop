/**
 * Run 状态的文案与语义色映射（列表/详情共用，docs/tech/08 §4.3）。
 */
package com.example.autop.feature.tasks

import androidx.annotation.StringRes
import com.example.autop.core.ui.theme.StatusTone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 任务详情/运行详情共用的运行记录条数上限。 */
internal const val RUN_LIMIT: Int = 50

private const val TIME_PATTERN = "MM-dd HH:mm"

/** 时间展示（MM-dd HH:mm）。 */
internal fun formatTime(millis: Long): String = SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(Date(millis))

/** Run 状态名（[com.example.autop.task.RunStatus]）→ 文案资源。 */
@StringRes
internal fun runStatusTextRes(statusName: String): Int =
    when (statusName) {
        "QUEUED" -> R.string.run_status_QUEUED
        "WAITING_SCREEN" -> R.string.run_status_WAITING_SCREEN
        "WAITING_USER" -> R.string.run_status_WAITING_USER
        "RUNNING" -> R.string.run_status_RUNNING
        "PAUSED" -> R.string.run_status_PAUSED
        "SUCCEEDED" -> R.string.run_status_SUCCEEDED
        "FAILED" -> R.string.run_status_FAILED
        "CANCELLED" -> R.string.run_status_CANCELLED
        "EXPIRED" -> R.string.run_status_EXPIRED
        "INTERRUPTED" -> R.string.run_status_INTERRUPTED
        "BUDGET_EXHAUSTED" -> R.string.run_status_BUDGET_EXHAUSTED
        "BREAKER_STOPPED" -> R.string.run_status_BREAKER_STOPPED
        else -> R.string.run_status_CANCELLED
    }

/** Run 状态名 → 语义色。 */
internal fun runStatusTone(statusName: String): StatusTone =
    when (statusName) {
        "RUNNING" -> StatusTone.RUNNING
        "WAITING_SCREEN", "WAITING_USER" -> StatusTone.WAITING
        "PAUSED" -> StatusTone.PAUSED
        "SUCCEEDED" -> StatusTone.SUCCEEDED
        "FAILED", "BUDGET_EXHAUSTED", "BREAKER_STOPPED" -> StatusTone.BLOCKED
        else -> StatusTone.NEUTRAL
    }
