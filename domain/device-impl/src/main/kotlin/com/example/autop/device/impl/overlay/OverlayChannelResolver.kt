/**
 * 悬浮窗通道解析（docs/tech/05 §10、ADR-0004）：按配置 + quirks + 能力查询结果
 * 决定当前通道，并在服务连接 / 权限 / 配置变化时发出新通道。
 */
package com.example.autop.device.impl.overlay

import com.example.autop.core.common.DeviceQuirks
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import com.example.autop.device.OverlayPermission
import com.example.autop.device.impl.ServiceHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 通道解析器：核心规则在 [OverlayChannelSelector]（纯函数）。 */
@Singleton
public class OverlayChannelResolver
    @Inject
    constructor(
        private val serviceHolder: ServiceHolder,
        private val permission: OverlayPermission,
        private val configProvider: ConfigProvider,
        private val quirks: DeviceQuirks,
    ) {
        /** 当前应使用的通道（null = 都不可用，不显示悬浮窗）。 */
        public fun current(): OverlayChannel? =
            OverlayChannelSelector.select(
                configuredType = configProvider.current()[UiConfig.OVERLAY].windowType,
                quirkType = quirks.value(QUIRK_OVERLAY_WINDOW_TYPE),
                accessibilityConnected = serviceHolder.connected.value,
                overlayPermissionGranted = permission.granted(),
            )

        /** 通道变化流：三个影响因素任一变化时重算并发出（去重）。 */
        public fun channelChanges(): Flow<OverlayChannel?> =
            combine(
                serviceHolder.connected,
                permission.changes,
                configProvider.observe().map { it[UiConfig.OVERLAY].windowType },
            ) { _, _, _ -> current() }
                .distinctUntilChanged()

        private companion object {
            const val QUIRK_OVERLAY_WINDOW_TYPE = "overlayWindowType"
        }
    }
