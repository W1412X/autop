/**
 * 配对码回填的前台服务（参照 Shizuku AdbPairingService）：通知动作经 getForegroundService 启动，
 * 由前台服务执行无线调试配对与注入通道连接。广播接收器只有约 10 秒，配对可能超过，所以改用服务。
 */
package com.example.autop.device.impl.shell

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.example.autop.core.common.Outcome
import com.example.autop.core.platform.DataSchemaState
import com.example.autop.device.ShellInput
import com.example.autop.device.impl.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
public class PairingReplyService : Service() {
    @Inject internal lateinit var shellInput: ShellInput

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // 通过 getForegroundService 启动时，必须先调用 startForeground，否则系统会判定超时。
        startForeground(NOTIF_WORKING, workingNotification())
        val port = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0
        val code =
            intent
                ?.let { RemoteInput.getResultsFromIntent(it) }
                ?.getCharSequence(PairingDiscoveryImpl.KEY_PAIR_CODE)
                ?.toString()
                ?.trim()
        if (port <= 0 || code.isNullOrEmpty() || !DataSchemaState.isReady()) {
            finish(success = false, startId = startId)
            return START_NOT_STICKY
        }
        scope.launch {
            val paired = shellInput.pair(port, code) is Outcome.Ok
            if (paired) shellInput.connect()
            finish(success = paired, startId = startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 结束前台状态并给出结果通知（同一通道；发现通知在配对成功后会由监听侧撤掉）。 */
    private fun finish(
        success: Boolean,
        startId: Int,
    ) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val text = getString(if (success) R.string.pairing_result_ok else R.string.pairing_result_failed)
        // 发送结果需要通知权限；未授予时静默跳过，状态以注入通道页为准。
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NOTIF_RESULT, resultNotification(text))
        }
        stopSelf(startId)
    }

    private fun workingNotification(): Notification =
        NotificationCompat
            .Builder(this, PairingDiscoveryImpl.CHANNEL_PAIRING)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.pairing_working))
            .setOngoing(true)
            .build()

    private fun resultNotification(text: String): Notification =
        NotificationCompat
            .Builder(this, PairingDiscoveryImpl.CHANNEL_PAIRING)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentText(text)
            .setAutoCancel(true)
            .build()

    public companion object {
        /** 通知携带的发现端口。 */
        public const val EXTRA_PORT: String = "port"

        private const val NOTIF_WORKING: Int = 0xadb2
        private const val NOTIF_RESULT: Int = 0xadb3
    }
}
