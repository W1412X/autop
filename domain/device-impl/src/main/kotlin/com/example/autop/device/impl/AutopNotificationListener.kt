/**
 * 通知监听服务（docs/tech/05 §9）：转发到 [NotificationFeedImpl]；
 * 忽略本应用与（配置开启时）常驻通知。
 */
package com.example.autop.device.impl

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.platform.DataSchemaState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 通知监听服务。 */
@AndroidEntryPoint
public class AutopNotificationListener : NotificationListenerService() {
    @Inject
    internal lateinit var feed: NotificationFeedImpl

    @Inject
    internal lateinit var configProvider: ConfigProvider

    @Inject
    internal lateinit var logger: Logger

    // lazy：注入发生在生命周期回调之后，字段初始化时 logger 尚未就绪。
    private val scope by lazy {
        CoroutineScope(SupervisorJob() + logger.exceptionHandler("NotificationListener"))
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        feed.setConnected(true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        feed.setConnected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!DataSchemaState.isReady()) return
        val info = parse(sbn) ?: return
        scope.launch { feed.onPosted(info) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        scope.launch { feed.onRemoved(sbn.key) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 过滤 + 解析（过滤不通过返回 null）。 */
    private fun parse(sbn: StatusBarNotification): com.example.autop.device.NotificationInfo? {
        if (sbn.packageName == packageName) return null
        val cfg = configProvider.current()[DeviceConfig.NOTIFICATIONS]
        val info = NotificationParser.parse(this, sbn, cfg.maxMessages)
        return if (info.isOngoing && cfg.ignoreOngoing) null else info
    }
}
