/**
 * 内置触发源（docs/tech/07 §1.4）：`trigger.notification`、`trigger.app_foreground`、
 * `trigger.screen_unlock`、`trigger.charging`、`trigger.network`。
 * `trigger.schedule` 由 AlarmScheduler 直接投递（见 TriggerHub.onScheduleFired）。
 */
package com.example.autop.task.impl.sources

import com.example.autop.device.DeviceStateProvider
import com.example.autop.device.NotificationFeed
import com.example.autop.device.NotificationInfo
import com.example.autop.task.TriggerEvent
import com.example.autop.task.TriggerSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan

/** `trigger.notification`（字段见 docs/tech/07 §1.4 表）。 */
public class NotificationTriggerSource(
    private val feed: NotificationFeed,
) : TriggerSource {
    override val id: String = ID
    override val fieldNames: List<String> = FIELDS
    override val requiresPermission: String? = "notification_listener"

    override fun events(): Flow<TriggerEvent> = feed.posted.map { n -> eventOf(n) }

    public companion object {
        public const val ID: String = "trigger.notification"
        public val FIELDS: List<String> =
            listOf(
                "package",
                "appLabel",
                "title",
                "text",
                "bigText",
                "conversationTitle",
                "isGroup",
                "sender",
                "category",
                "key",
            )

        /** 通知 → 事件字段映射（试运行的最近事件复用）。 */
        public fun eventOf(n: NotificationInfo): TriggerEvent =
            TriggerEvent(
                sourceId = ID,
                key = n.key,
                occurredAt = n.postedAt,
                fields =
                    buildMap {
                        n.packageName.let { put("package", it) }
                        n.appLabel?.let { put("appLabel", it) }
                        n.title?.let { put("title", it) }
                        n.text?.let { put("text", it) }
                        n.bigText?.let { put("bigText", it) }
                        n.conversationTitle?.let { put("conversationTitle", it) }
                        put("isGroup", n.isGroupConversation.toString())
                        n.messages
                            .lastOrNull()
                            ?.sender
                            ?.let { put("sender", it) }
                        n.category?.let { put("category", it) }
                        put("key", n.key)
                    },
            )
    }
}

/** `trigger.app_foreground`：前台包名变化（来源 DeviceStateProvider.changes）。 */
public class AppForegroundSource(
    private val deviceState: DeviceStateProvider,
) : TriggerSource {
    override val id: String = ID
    override val fieldNames: List<String> = listOf("package")
    override val requiresPermission: String? = null

    override fun events(): Flow<TriggerEvent> =
        deviceState.changes
            .scan(null as String?) { prev, state -> state.foregroundPackage?.takeIf { it != prev } ?: prev }
            .filter { it != null }
            .map { pkg ->
                TriggerEvent(
                    sourceId = ID,
                    key = pkg.orEmpty(),
                    occurredAt = System.currentTimeMillis(),
                    fields = mapOf("package" to pkg.orEmpty()),
                )
            }

    public companion object {
        public const val ID: String = "trigger.app_foreground"
    }
}

/** `trigger.charging`：充电状态变化。 */
public class ChargingSource(
    private val deviceState: DeviceStateProvider,
) : TriggerSource {
    override val id: String = ID
    override val fieldNames: List<String> = listOf("charging", "battery")
    override val requiresPermission: String? = null

    override fun events(): Flow<TriggerEvent> =
        deviceState.changes
            .scan(null as Boolean?) { prev, state -> state.charging.takeIf { it != prev } ?: prev }
            .filter { it != null }
            .map { charging ->
                TriggerEvent(
                    sourceId = ID,
                    key = charging.toString(),
                    occurredAt = System.currentTimeMillis(),
                    fields =
                        mapOf(
                            "charging" to charging.toString(),
                            "battery" to (deviceState.current().batteryPercent?.toString() ?: ""),
                        ),
                )
            }

    public companion object {
        public const val ID: String = "trigger.charging"
    }
}

/** `trigger.network`：网络类型变化。 */
public class NetworkSource(
    private val deviceState: DeviceStateProvider,
) : TriggerSource {
    override val id: String = ID
    override val fieldNames: List<String> = listOf("type")
    override val requiresPermission: String? = null

    override fun events(): Flow<TriggerEvent> =
        deviceState.changes
            .scan(null as String?) { prev, state -> state.networkType.takeIf { it != prev } ?: prev }
            .filter { it != null }
            .map { type ->
                TriggerEvent(
                    sourceId = ID,
                    key = type.orEmpty(),
                    occurredAt = System.currentTimeMillis(),
                    fields = mapOf("type" to type.orEmpty()),
                )
            }

    public companion object {
        public const val ID: String = "trigger.network"
    }
}

/** 解锁事件总线（ACTION_USER_PRESENT 广播 → 源）。 */
public object UnlockBus {
    public val events: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)
}

/** `trigger.screen_unlock`：解锁广播。 */
public class ScreenUnlockSource : TriggerSource {
    override val id: String = ID
    override val fieldNames: List<String> = emptyList()
    override val requiresPermission: String? = null

    override fun events(): Flow<TriggerEvent> =
        UnlockBus.events.map {
            TriggerEvent(
                sourceId = ID,
                key = "",
                occurredAt = System.currentTimeMillis(),
                fields = emptyMap(),
            )
        }

    public companion object {
        public const val ID: String = "trigger.screen_unlock"
    }
}
