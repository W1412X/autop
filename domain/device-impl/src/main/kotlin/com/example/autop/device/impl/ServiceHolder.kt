/**
 * 无障碍服务持有者（docs/tech/05 §2.2）：服务注册/注销与连接状态。
 */
package com.example.autop.device.impl

import com.example.autop.device.AccessibilityStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 持有当前 [AutopAccessibilityService] 实例并实现 [AccessibilityStatus]。 */
@Singleton
public class ServiceHolder
    @Inject
    constructor() : AccessibilityStatus {
        private val serviceRef = MutableStateFlow<AutopAccessibilityService?>(null)
        private val connectedState = MutableStateFlow(false)

        /** 当前服务实例；未连接为 null。 */
        public val service: StateFlow<AutopAccessibilityService?> = serviceRef.asStateFlow()

        override val connected: StateFlow<Boolean> = connectedState.asStateFlow()

        /** 当前服务实例（同步读取）。 */
        public fun current(): AutopAccessibilityService? = serviceRef.value

        /** 服务连接时注册（onServiceConnected）。 */
        public fun register(service: AutopAccessibilityService) {
            serviceRef.value = service
            connectedState.value = true
        }

        /** 服务断开时移除（onUnbind / onDestroy）。 */
        public fun unregister() {
            serviceRef.value = null
            connectedState.value = false
        }
    }
