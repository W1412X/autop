/**
 * [PairingDiscovery] 实现：未配对时用 [AdbMdns] 监听 `_adb-tls-pairing._tcp`
 * （系统无线调试「使用配对码配对设备」窗口开启时广播），发现即发通知——
 * 通知点按深链到注入通道页（端口自动就绪），或直接在通知栏内联回填 6 位配对码
 * （[PairingReplyReceiver]）。配对成功后停用监听并撤掉通知。
 */
package com.example.autop.device.impl.shell

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.autop.device.PairingDiscovery
import com.example.autop.device.ShellInput
import com.example.autop.device.impl.R
import com.example.autop.device.impl.adb.AdbMdns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 配对发现器（进程级；未配对时低频监听，发现即通知，配对成功即停）。 */
@Singleton
public class PairingDiscoveryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val shellInput: ShellInput,
    ) : PairingDiscovery {
        private val manager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        private val _discoveredPort = MutableStateFlow<Int?>(null)

        override val discoveredPort: StateFlow<Int?> = _discoveredPort.asStateFlow()

        private var mdns: AdbMdns? = null
        private var job: Job? = null

        /** 已通知过的端口（同端口不重复打扰；配对成功后清空）。 */
        private val notifiedPorts = mutableSetOf<Int>()

        @Synchronized
        override fun start(scope: CoroutineScope) {
            if (job?.isActive == true) return
            job =
                scope.launch {
                    shellInput.paired.collect { paired ->
                        if (paired) {
                            teardown()
                        } else {
                            ensureDiscovery()
                        }
                    }
                }
        }

        @Synchronized
        override fun stop() {
            job?.cancel()
            job = null
            teardown()
        }

        /** 手动重启一轮发现（配对卡"重新搜索"：错过广播/进程回收后自愈）。 */
        @Synchronized
        override fun refresh() {
            if (shellInput.paired.value) return
            mdns?.stop()
            mdns = null
            _discoveredPort.value = null
            ensureDiscovery()
        }

        @Synchronized
        private fun teardown() {
            mdns?.stop()
            mdns = null
            _discoveredPort.value = null
            notifiedPorts.clear()
            manager.cancel(NOTIF_PAIRING)
        }

        @Synchronized
        private fun ensureDiscovery() {
            if (mdns != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            mdns =
                AdbMdns(context, AdbMdns.TLS_PAIRING) { port -> onPort(port) }.also { it.start() }
        }

        /** 发现回调（mDNS 线程）：记录端口，同端口只通知一次。 */
        @Synchronized
        private fun onPort(port: Int) {
            if (port <= 0) {
                _discoveredPort.value = null
                return
            }
            _discoveredPort.value = port
            if (notifiedPorts.add(port)) notifyPairing(port)
        }

        /** 发现通知：点按深链到注入通道页；通知栏内联回填配对码即可完成配对。 */
        private fun notifyPairing(port: Int) {
            ensureChannel()
            val builder =
                NotificationCompat
                    .Builder(context, CHANNEL_PAIRING)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setAutoCancel(true)
                    .setContentTitle(context.getString(R.string.pairing_notif_title))
                    .setContentText(context.getString(R.string.pairing_notif_text, port))
                    .setContentIntent(openShellIntent())
                    .addAction(replyAction(port))
            manager.notify(NOTIF_PAIRING, builder.build())
        }

        /** 内联回填配对码动作（RemoteInput）。 */
        private fun replyAction(port: Int): NotificationCompat.Action {
            val input =
                RemoteInput
                    .Builder(KEY_PAIR_CODE)
                    .setLabel(context.getString(R.string.pairing_reply_hint))
                    .build()
            val intent =
                Intent(context, PairingReplyReceiver::class.java)
                    .putExtra(PairingReplyReceiver.EXTRA_PORT, port)
            val pending =
                PendingIntent.getBroadcast(
                    context,
                    port,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            return NotificationCompat
                .Action
                .Builder(0, context.getString(R.string.pairing_reply_action), pending)
                .addRemoteInput(input)
                .build()
        }

        /** 点按通知：启动应用并深链注入通道子页（路由 extra 由 MainActivity 消费）。 */
        private fun openShellIntent(): PendingIntent {
            val intent =
                context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                    ?.apply {
                        putExtra(PairingDiscovery.EXTRA_OPEN_ROUTE, PairingDiscovery.OPEN_ROUTE_SHELL)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }

        private fun ensureChannel() {
            if (manager.getNotificationChannel(CHANNEL_PAIRING) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PAIRING,
                    context.getString(R.string.pairing_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }

        public companion object {
            /** 发现通知 id（单条覆盖更新）。 */
            public const val NOTIF_PAIRING: Int = 0xadb1

            /** 通知通道 id。 */
            public const val CHANNEL_PAIRING: String = "adb_pairing"

            /** RemoteInput 配对码键（与 [PairingReplyReceiver] 共用）。 */
            public const val KEY_PAIR_CODE: String = "pairCode"
        }
    }
