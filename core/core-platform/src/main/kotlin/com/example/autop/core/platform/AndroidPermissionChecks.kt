package com.example.autop.core.platform

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import com.example.autop.core.common.Capability
import com.example.autop.core.common.PermissionChecks
import com.example.autop.core.common.PlatformCapabilities

/**
 * [PermissionChecks] 的 Android 实现（docs/tech/02 §4.1；WP11 引导与健康检查）。
 * SDK 判断经 [PlatformCapabilities] 能力查询包装，本类是允许读平台 API 的两个位置之一。
 */
public class AndroidPermissionChecks(
    private val context: Context,
    private val capabilities: PlatformCapabilities,
) : PermissionChecks {
    override fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    override fun canPostNotifications(): Boolean =
        if (capabilities.has(Capability.NOTIFICATION_RUNTIME_PERMISSION)) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    override fun canReadNotifications(): Boolean =
        parseListenerPackages(
            Settings.Secure.getString(context.contentResolver, ENABLED_NOTIFICATION_LISTENERS),
        ).contains(context.packageName)

    @SuppressLint("NewApi") // FULL_SCREEN_INTENT_NEEDS_GRANT 闸门已隐含 SDK≥34；SDK_INT 仅在 PlatformCapabilitiesImpl 读取
    override fun canUseFullScreenIntent(): Boolean =
        if (capabilities.has(Capability.FULL_SCREEN_INTENT_NEEDS_GRANT)) {
            notificationManager().canUseFullScreenIntent()
        } else {
            true
        }

    override fun isIgnoringBatteryOptimizations(): Boolean =
        powerManager().isIgnoringBatteryOptimizations(context.packageName)

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun powerManager(): PowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private companion object {
        /** 通知使用权授权名单设置键（Settings.Secure，`pkg/cls:pkg/cls` 格式）。 */
        const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
    }
}

/** 解析 `enabled_notification_listeners` 值为包名集合（N190：授权判定查名单，不信服务连接态）。 */
internal fun parseListenerPackages(value: String?): Set<String> =
    value
        ?.split(':')
        ?.mapNotNull { entry -> entry.substringBefore('/').takeIf { it.isNotBlank() } }
        ?.toSet()
        .orEmpty()
