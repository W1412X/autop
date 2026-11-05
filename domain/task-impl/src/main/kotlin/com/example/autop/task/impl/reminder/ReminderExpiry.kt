/**
 * 锁屏提醒超时收口（docs/tech/07 §4.4 第 5 条）：定时任务 `expireMinutes.schedule`、
 * 条件任务 `...condition`；到期 SnoozeReceiver 把 Run 置 EXPIRED。
 */
package com.example.autop.task.impl.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.autop.core.common.Clock
import com.example.autop.core.common.RunId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.TaskConfig
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord

/** 超时闹钟。 */
public class ReminderExpiry(
    private val context: Context,
    private val configProvider: ConfigProvider,
    private val clock: Clock,
) {
    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** 该 Run 的提醒过期时刻（定时/条件不同）。 */
    public fun expireAt(record: RunRecord): Long {
        val reminder = configProvider.current()[TaskConfig.REMINDER]
        val minutes =
            if (record.origin == RunOrigin.SCHEDULE) {
                reminder.expireMinutes.schedule
            } else {
                reminder.expireMinutes.condition
            }
        return clock.nowMillis() + minutes * MINUTE_MS
    }

    /** 安排超时收口。 */
    public fun schedule(record: RunRecord) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            expireAt(record),
            PendingIntent.getBroadcast(
                context,
                (EXPIRE_KEY + record.id.value).hashCode(),
                intentOf(record.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    /** 取消超时闹钟。 */
    public fun cancel(runId: RunId) {
        alarmManager.cancel(
            PendingIntent.getBroadcast(
                context,
                (EXPIRE_KEY + runId.value).hashCode(),
                intentOf(runId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    private fun intentOf(runId: RunId): Intent =
        Intent(context, SnoozeReceiver::class.java)
            .putExtra(SnoozeReceiver.EXTRA_RUN_ID, runId.value)
            .putExtra(SnoozeReceiver.EXTRA_EXPIRE, true)

    private companion object {
        const val MINUTE_MS = 60_000L
        const val EXPIRE_KEY = "expire-"
    }
}
