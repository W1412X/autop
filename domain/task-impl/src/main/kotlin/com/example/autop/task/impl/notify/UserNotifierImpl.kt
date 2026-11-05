/**
 * 用户通知实现（docs/tech/07 §7）：Run 结束（`result`）、策略拦截（`alert`，高）、
 * 需要无障碍（`alert`）、notify_user 工具（`result`）。
 */
package com.example.autop.task.impl.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.autop.core.common.RunId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.TaskConfig
import com.example.autop.task.RunRecord
import com.example.autop.task.UserNotifier
import com.example.autop.task.impl.R
import dagger.hilt.android.qualifiers.ApplicationContext

/** 用户通知。 */
public class UserNotifierImpl(
    @ApplicationContext private val context: Context,
    private val configProvider: ConfigProvider,
) : UserNotifier {
    private val manager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun notifyResult(run: RunRecord) {
        ensureChannels()
        val summary = run.summary.orEmpty().take(configProvider.current()[TaskConfig.NOTIFY].summaryChars)
        notify(
            channel = CHANNEL_RESULT,
            id = run.id.value.hashCode(),
            title = context.getString(R.string.notify_result_title),
            text = summary,
            runId = run.id,
        )
    }

    override fun notifyMessage(
        title: String,
        text: String,
        runId: RunId?,
    ) {
        ensureChannels()
        notify(CHANNEL_RESULT, id = (title + text).hashCode(), title = title, text = text, runId = runId)
    }

    /** 策略拦截（docs/tech/07 §7）：原因 + “我已手动完成，继续”/“结束”。 */
    public fun notifyBlocked(
        run: RunRecord,
        reasonText: String,
    ) {
        ensureChannels()
        val builder =
            base(CHANNEL_ALERT)
                .setContentTitle(context.getString(R.string.notify_blocked_title))
                .setContentText(reasonText)
                .addAction(
                    0,
                    context.getString(R.string.notify_blocked_continue),
                    actionIntent(run.id, ACTION_RESUME),
                ).addAction(0, context.getString(R.string.notify_blocked_end), actionIntent(run.id, ACTION_CANCEL))
        manager.notify(run.id.value.hashCode(), builder.build())
    }

    /** 需要无障碍服务。 */
    public fun notifyAccessibilityNeeded(text: String) {
        ensureChannels()
        val builder =
            base(CHANNEL_ALERT)
                .setContentTitle(context.getString(R.string.notify_a11y_title))
                .setContentText(text)
        manager.notify(NOTIF_A11Y, builder.build())
    }

    private fun notify(
        channel: String,
        id: Int,
        title: String,
        text: String,
        runId: RunId?,
    ) {
        val builder =
            base(channel)
                .setContentTitle(title)
                .setContentText(text)
        if (runId != null) builder.setContentIntent(openIntent())
        manager.notify(id, builder.build())
    }

    private fun base(channel: String): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)

    private fun openIntent(): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(
        runId: RunId,
        action: String,
    ): PendingIntent {
        val intent =
            Intent(context, NotifyActionReceiver::class.java)
                .setAction(action)
                .putExtra(NotifyActionReceiver.EXTRA_RUN_ID, runId.value)
        return PendingIntent.getBroadcast(
            context,
            (action + runId.value).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannels() {
        if (manager.getNotificationChannel(CHANNEL_RESULT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RESULT,
                    context.getString(R.string.notify_channel_result),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ALERT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERT,
                    context.getString(R.string.notify_channel_alert),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    public companion object {
        public const val CHANNEL_RESULT: String = "result"
        public const val CHANNEL_ALERT: String = "alert"
        public const val ACTION_RESUME: String = "resume"
        public const val ACTION_CANCEL: String = "cancel"
        private const val NOTIF_A11Y = 7201
    }
}
