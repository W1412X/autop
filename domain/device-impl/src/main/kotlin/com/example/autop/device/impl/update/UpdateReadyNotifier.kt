/**
 * "新版本已下载"系统通知：应用在后台时发出，点按打开应用；安装由前台的自动拉起接手
 * （见 UpdatePromptViewModel）。固定通知 id，重复发送只更新同一条，不重复提醒。
 */
package com.example.autop.device.impl.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.autop.device.impl.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_ID = "app_update"
private const val NOTIF_ID = 4201

/** 更新已就绪通知的发送与撤销。 */
@Singleton
public class UpdateReadyNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val manager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        /** 发送（通知总开关关闭时静默跳过，安装仍会在下次回到前台时自动拉起）。 */
        public fun notify(versionName: String) {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            ensureChannel()
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            val pending =
                PendingIntent.getActivity(
                    context,
                    0,
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(context.getString(R.string.update_ready_title))
                    .setContentText(context.getString(R.string.update_ready_text, versionName))
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build()
            runCatching { manager.notify(NOTIF_ID, notification) }
        }

        /** 撤销（开始安装后调用）。 */
        public fun cancel() {
            manager.cancel(NOTIF_ID)
        }

        private fun ensureChannel() {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }
