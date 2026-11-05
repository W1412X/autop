/**
 * 锁屏提醒通知的 PendingIntents（docs/tech/07 §4.4）：执行/稍后/跳过三动作、
 * 打开应用与 fullScreenIntent（`canUseFullScreenIntent()` 为 false 时不设）。
 */
package com.example.autop.task.impl.reminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.autop.task.impl.R

/** 提醒通知的 Intent 构造。 */
public class ReminderIntents(
    private val context: Context,
    private val manager: NotificationManager,
) {
    /** 三个动作按钮（执行需解锁）。 */
    public fun actions(
        firstRunId: String?,
        unlocked: Boolean,
    ): List<NotificationCompat.Action> {
        if (firstRunId == null) return emptyList()
        return listOf(
            action(ReminderActionReceiver.ACTION_EXECUTE, R.string.reminder_action_execute, firstRunId, unlocked),
            action(ReminderActionReceiver.ACTION_SNOOZE, R.string.reminder_action_snooze, firstRunId, unlocked),
            action(ReminderActionReceiver.ACTION_SKIP, R.string.reminder_action_skip, firstRunId, unlocked),
        )
    }

    /** 打开应用。 */
    public fun openIntent(): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /** fullScreenIntent（权限不满足返回 null）。 */
    public fun fullScreenPending(): PendingIntent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !manager.canUseFullScreenIntent()) {
            return null
        }
        val intent = Intent(context, ReminderActivity::class.java)
        return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(
        action: String,
        label: Int,
        runId: String,
        unlocked: Boolean,
    ): NotificationCompat.Action {
        val intent =
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(action)
                .putExtra(ReminderActionReceiver.EXTRA_RUN_ID, runId)
        return NotificationCompat.Action
            .Builder(
                0,
                context.getString(label),
                PendingIntent.getBroadcast(
                    context,
                    (action + runId).hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).setAuthenticationRequired(action == ReminderActionReceiver.ACTION_EXECUTE && !unlocked)
            .build()
    }
}
