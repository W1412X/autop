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
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.TaskConfig
import com.example.autop.task.RunRecord
import com.example.autop.task.ThreadLaunchExtras
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
            threadId = run.threadId,
        )
    }

    public fun notifyFlowSuggestion(runId: RunId) {
        notifyMessage(
            title = context.getString(R.string.notify_flow_suggest_title),
            text = context.getString(R.string.notify_flow_suggest_text),
            runId = runId,
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
    override fun notifyBlocked(run: RunRecord) {
        ensureChannels()
        val builder =
            base(CHANNEL_ALERT)
                .setContentTitle(context.getString(R.string.notify_blocked_title))
                .setContentText(context.getString(R.string.notify_blocked_text))
                .setContentIntent(openIntent(run.threadId))
                .addAction(
                    0,
                    context.getString(R.string.notify_blocked_continue),
                    actionIntent(run.id, ACTION_RESUME),
                ).addAction(0, context.getString(R.string.notify_blocked_end), actionIntent(run.id, ACTION_CANCEL))
        manager.notify(run.id.value.hashCode(), builder.build())
    }

    override fun notifyQuestion(
        run: RunRecord,
        question: String,
    ) {
        ensureChannels()
        val builder =
            base(CHANNEL_ALERT)
                .setContentTitle(context.getString(R.string.notify_question_title))
                .setContentText(question)
                .setContentIntent(openIntent(run.threadId))
        manager.notify(questionNotifId(run.id), builder.build())
    }

    override fun cancelQuestion(runId: RunId) {
        manager.cancel(questionNotifId(runId))
    }

    private fun questionNotifId(runId: RunId): Int = (QUESTION_KEY_PREFIX + runId.value).hashCode()

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
        threadId: ThreadId? = null,
    ) {
        val builder =
            base(channel)
                .setContentTitle(title)
                .setContentText(text)
        if (runId != null) builder.setContentIntent(openIntent(threadId))
        manager.notify(id, builder.build())
    }

    private fun base(channel: String): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)

    /** 点击通知打开应用；带 [threadId] 时直接进入该对话。不同对话使用不同请求码，避免共用 extra。 */
    private fun openIntent(threadId: ThreadId?): PendingIntent {
        val intent =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                if (threadId != null) putExtra(ThreadLaunchExtras.EXTRA_THREAD_ID, threadId.value)
            }
        return PendingIntent.getActivity(
            context,
            (OPEN_KEY_PREFIX + (threadId?.value.orEmpty())).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
        private const val QUESTION_KEY_PREFIX = "question:"
        private const val OPEN_KEY_PREFIX = "open:"
    }
}
