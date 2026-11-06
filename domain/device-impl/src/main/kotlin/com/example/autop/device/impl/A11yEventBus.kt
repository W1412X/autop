/**
 * 无障碍事件总线（docs/tech/05 §2.2）：只传播轻量副本，不携带 AccessibilityEvent 对象。
 */
package com.example.autop.device.impl

import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 无障碍事件的轻量副本（AccessibilityEvent 会被系统回收，不能进流）。 */
public data class A11yEventLite(
    /** AccessibilityEvent.eventType。 */
    val type: Int,
    val packageName: String?,
    val className: String?,
    /** AccessibilityEvent.eventTime。 */
    val time: Long,
    /** 是否由本应用自身界面产生（触摸检测需排除）。 */
    val isSelfOrigin: Boolean,
    /** 事件源节点的快照；只在录制期间采集（见 [RecordingGate]），其他情况为 null。 */
    val source: EventSource? = null,
    /** 滚动事件的纵向增量（正数为向下滚动内容）；非滚动事件或未采集时为 null。 */
    val scrollDeltaY: Int? = null,
)

/** 事件源节点快照（在 AccessibilityEvent 被回收前复制）。 */
public data class EventSource(
    val className: String?,
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: com.example.autop.device.Rect?,
    val isPassword: Boolean,
)

/** 无障碍事件总线：容量取 `device.events.buffer`，溢出丢最旧。 */
@Singleton
public class A11yEventBus
    @Inject
    constructor(
        configProvider: ConfigProvider,
    ) {
        private val eventsFlow =
            MutableSharedFlow<A11yEventLite>(
                extraBufferCapacity = configProvider.current()[DeviceConfig.EVENTS].buffer,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /** 事件流，供 UiStabilizer、触摸检测等订阅。 */
        public val events: SharedFlow<A11yEventLite> = eventsFlow.asSharedFlow()

        /** 推入事件（永不挂起；缓冲满时丢最旧）。 */
        public fun emit(event: A11yEventLite) {
            eventsFlow.tryEmit(event)
        }
    }
