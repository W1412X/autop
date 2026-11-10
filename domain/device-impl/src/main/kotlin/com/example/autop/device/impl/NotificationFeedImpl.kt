/**
 * 通知流实现（docs/tech/05 §9）：内存环形缓冲 + `posted` 广播 + 连接状态，
 * 由 [AutopNotificationListener] 写入。
 */
package com.example.autop.device.impl

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.NotificationFeed
import com.example.autop.device.NotificationInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 通知流实现（单例，监听服务与工具共用）。 */
@Singleton
public class NotificationFeedImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val configProvider: ConfigProvider,
    ) : NotificationFeed {
        private val mutex = Mutex()

        /** 环形缓冲（旧→新）。 */
        private val buffer = ArrayDeque<NotificationInfo>()

        /** 环形缓冲是否溢出过（WP7.10：溢出后更早的通知读不到，读取方要提示截断）。 */
        private var overflowed = false

        /** 当前仍显示的通知（key → info）。 */
        private val actives = LinkedHashMap<String, NotificationInfo>()

        private val _posted =
            MutableSharedFlow<NotificationInfo>(
                extraBufferCapacity = POSTED_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        override val posted: SharedFlow<NotificationInfo> = _posted

        private val _connected = MutableStateFlow(false)

        override val connected: StateFlow<Boolean> = _connected

        override fun requestRebind() {
            NotificationListenerService.requestRebind(ComponentName(context, AutopNotificationListener::class.java))
        }

        override suspend fun active(packageName: String?): List<NotificationInfo> =
            mutex.withLock {
                actives.values.filter { packageName == null || it.packageName == packageName }
            }

        override suspend fun recent(
            packageName: String?,
            sinceMs: Long?,
            limit: Int,
        ): List<NotificationInfo> =
            mutex.withLock {
                buffer
                    .filter { matches(it, packageName, sinceMs) }
                    .takeLast(limit)
            }

        /** 最早保留的通知时间（WP7.10）：溢出后为缓冲首条的 postedAt，没溢出过为 null。 */
        override suspend fun earliestRetainedAt(): Long? =
            mutex.withLock {
                if (overflowed) buffer.firstOrNull()?.postedAt else null
            }

        /** 监听服务上报新通知：入环形缓冲并广播。 */
        public suspend fun onPosted(info: NotificationInfo) {
            mutex.withLock {
                while (buffer.size >= maxBuffer()) {
                    buffer.removeFirst()
                    overflowed = true
                }
                buffer.addLast(info)
                actives[info.key] = info
            }
            _posted.emit(info)
        }

        /** 通知被移除。 */
        public suspend fun onRemoved(key: String) {
            mutex.withLock { actives.remove(key) }
        }

        /** 监听服务连接状态。 */
        public fun setConnected(value: Boolean) {
            _connected.value = value
        }

        private fun matches(
            info: NotificationInfo,
            packageName: String?,
            sinceMs: Long?,
        ): Boolean =
            (packageName == null || info.packageName == packageName) && (sinceMs == null || info.postedAt >= sinceMs)

        private fun maxBuffer(): Int = configProvider.current()[DeviceConfig.NOTIFICATIONS].recentBuffer

        private companion object {
            const val POSTED_BUFFER = 64
        }
    }
