/**
 * 锁屏提醒（docs/tech/07 §4.4）：渠道 `reminder`（高重要性、闹钟铃声、振动）；
 * 内容为任务名与触发原因（trigger.title/text 前 `task.reminder.previewChars` 字）；
 * 多个等待中的 Run 合并为一条（列前 3 个）；fullScreenIntent → ReminderActivity；
 * 超时收口见 [ReminderExpiry]；用户自己解锁只把通知更新为“已解锁，点击执行”。
 */
package com.example.autop.task.impl.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.example.autop.core.common.Clock
import com.example.autop.core.common.RunId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.TaskConfig
import com.example.autop.task.RunRecord
import com.example.autop.task.impl.R
import dagger.hilt.android.qualifiers.ApplicationContext

/** 锁屏提醒。 */
public class LockReminder(
    @ApplicationContext private val context: Context,
    private val configProvider: ConfigProvider,
    clock: Clock,
) {
    private val manager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val expiry = ReminderExpiry(context, configProvider, clock)
    private val intents = ReminderIntents(context, manager)

    /** 等待提醒的 Run（合并展示）。 */
    private val waiting = LinkedHashMap<RunId, RunRecord>()

    /** 记录并展示提醒（多 Run 合并）；同时安排超时收口。 */
    public fun remind(record: RunRecord) {
        waiting[record.id] = record
        expiry.schedule(record)
        show()
    }

    /** Run 收口后移除。 */
    public fun dismiss(runId: RunId) {
        expiry.cancel(runId)
        if (waiting.remove(runId) != null) show()
    }

    /** 解锁后更新为“已解锁，点击执行”（docs/tech/07 §4.4 第 6 条）。 */
    public fun onUnlocked() {
        if (waiting.isNotEmpty()) show(unlocked = true)
    }

    /** 超时到期（由调度侧调用）：Run → EXPIRED 已在调用方处理，这里只收口通知。 */
    public fun expire(runId: RunId) = dismiss(runId)

    private fun show(unlocked: Boolean = false) {
        ensureChannel()
        val records = waiting.values.take(MAX_SHOWN)
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentTitle(title(unlocked))
                .setContentText(content(records))
                .setContentIntent(intents.openIntent())
        intents.actions(waiting.keys.firstOrNull()?.value, unlocked).forEach { builder.addAction(it) }
        val fullScreen = intents.fullScreenPending()
        if (fullScreen != null) builder.setFullScreenIntent(fullScreen, true)
        manager.notify(NOTIF_ID, builder.build())
    }

    private fun title(unlocked: Boolean): String =
        when {
            waiting.size > 1 -> context.getString(R.string.reminder_title_many, waiting.size)
            unlocked -> context.getString(R.string.reminder_title_unlocked)
            else -> context.getString(R.string.reminder_title)
        }

    private fun content(records: List<RunRecord>): String {
        val preview = configProvider.current()[TaskConfig.REMINDER].previewChars
        return records.joinToString("\n") { record ->
            val reason = triggerPreview(record).take(preview)
            if (reason.isEmpty()) {
                record.summary.orEmpty().take(preview)
            } else {
                reason
            }
        }
    }

    private fun triggerPreview(record: RunRecord): String =
        record.triggerContext[PREVIEW_TITLE]
            ?: record.triggerContext[PREVIEW_TEXT]
            ?: ""

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.reminder_channel_desc)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build(),
                )
                enableVibration(true)
            }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "reminder"
        const val NOTIF_ID = 7101
        const val MAX_SHOWN = 3
        const val PREVIEW_TITLE = "trigger.title"
        const val PREVIEW_TEXT = "trigger.text"
    }
}
