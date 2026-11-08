/**
 * 无线调试配对发现端口（mDNS `_adb-tls-pairing._tcp`）：未配对时后台发现系统
 * 「使用配对码配对设备」窗口广播的可配对服务，自动获得配对端口——供配对页
 * 自动填端口与「发现可配对设备」通知使用（配对成功后自动停用，不再监听）。
 */
package com.example.autop.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

public interface PairingDiscovery {
    /** 已发现的配对端口（未发现为 null；配对成功后清空）。 */
    public val discoveredPort: StateFlow<Int?>

    /** 启动后台发现（幂等；已配对时自动停用，监听开销仅为 mDNS 被动收包）。 */
    public fun start(scope: CoroutineScope)

    /** 停止发现并清理发现通知。 */
    public fun stop()

    public companion object {
        /** 通知点击深链的路由 extra 键（值为应用路由，如 [OPEN_ROUTE_SHELL]）。 */
        public const val EXTRA_OPEN_ROUTE: String = "open_route"

        /** 注入通道子页路由（`AutopRoutes.ME_SECTION` 的 shell 实例）。 */
        public const val OPEN_ROUTE_SHELL: String = "me/shell"
    }
}
