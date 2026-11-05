/**
 * 触摸检测实现（docs/tech/05 §8）：两路用户触摸来源——
 * 1) `outside_touch`：触摸探针窗口（1×1 透明，`FLAG_WATCH_OUTSIDE_TOUCH`）收 `ACTION_OUTSIDE`；
 * 2) `a11y_events`：无障碍事件（点击 / 滚动 / 长按）且非本应用界面。
 * 自身动作窗口内（GestureController 执行 + `device.touch.selfActionGraceMs` 宽限）的
 * 触摸不算用户操作；探针只在有 Run 持有 ScreenLease 时显示（省电）。
 *
 * 时间戳统一为墙钟（`Clock.nowMillis`）：a11y 事件用到达时刻近似事件时刻
 * （宽限窗口 400ms 量级远大于事件传递延迟），保持与 `markSelfActionWindow`
 * 及 `lastUserTouchAt` 消费方（07 §4.3 idleBeforeStartMs）同一基准。
 */
package com.example.autop.device.impl.touch

import android.view.accessibility.AccessibilityEvent
import com.example.autop.core.common.Clock
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlaySpec
import com.example.autop.device.ScreenLease
import com.example.autop.device.UserActivityMonitor
import com.example.autop.device.impl.A11yEventBus
import com.example.autop.device.impl.A11yEventLite
import com.example.autop.device.impl.overlay.OverlayWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** [UserActivityMonitor] 实现。 */
@Singleton
@Suppress("LongParameterList") // 端口聚合类：五个端口 + 时钟 + 调度器，不可再减（questions.md N114 口径）
public class UserActivityMonitorImpl
    @Inject
    constructor(
        private val overlay: OverlayWindowManager,
        private val eventBus: A11yEventBus,
        private val screenLease: ScreenLease,
        private val configProvider: ConfigProvider,
        private val clock: Clock,
        private val logger: Logger,
        dispatchers: DispatcherProvider,
    ) : UserActivityMonitor {
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.default + logger.exceptionHandler("UserActivityMonitor"))

        private val _userTouches =
            MutableSharedFlow<Long>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        override val userTouches: SharedFlow<Long> = _userTouches.asSharedFlow()

        /** 自身动作时间窗口（墙钟，startMs..endMs）；随手清理过期项。 */
        private val selfWindows = CopyOnWriteArrayList<Pair<Long, Long>>()

        @Volatile
        private var lastTouchAt: Long? = null

        init {
            overlay.register(TOUCH_PROBE_ID) { context, _ ->
                ProbeView(context) { x, y -> emitProbeTouch(x, y) }
            }
            scope.launch {
                screenLease.holder.collect { holder ->
                    if (holder != null && probeEnabled()) {
                        overlay.show(TOUCH_PROBE_ID, probeSpec())
                    } else {
                        overlay.hide(TOUCH_PROBE_ID)
                    }
                }
            }
            scope.launch {
                eventBus.events.collect { event -> onA11yEvent(event) }
            }
        }

        override fun lastUserTouchAt(): Long? = lastTouchAt

        override fun markSelfActionWindow(
            startMs: Long,
            endMs: Long,
        ) {
            selfWindows.removeAll { it.second < startMs }
            selfWindows.add(startMs to endMs)
        }

        private fun onA11yEvent(event: A11yEventLite) {
            if (!enabled() || SOURCE_A11Y_EVENTS !in sources()) return
            if (event.isSelfOrigin || event.type !in USER_TOUCH_EVENT_TYPES) return
            emitTouch()
        }

        private fun emitTouch() {
            if (!enabled()) return
            val now = clock.nowMillis()
            if (insideSelfWindow(selfWindows, now)) return
            lastTouchAt = now
            _userTouches.tryEmit(now)
        }

        /** 探针路径：触摸点落在自家 touchable 窗口（悬浮球 / 面板）内时不算用户触摸（N139）。 */
        private fun emitProbeTouch(
            x: Float,
            y: Float,
        ) {
            if (insideAny(overlay.touchableSpecs(), x.roundToInt(), y.roundToInt())) return
            emitTouch()
        }

        private fun enabled(): Boolean = configProvider.current()[DeviceConfig.TOUCH].enabled

        private fun sources(): List<String> = configProvider.current()[DeviceConfig.TOUCH].sources

        private fun probeEnabled(): Boolean = enabled() && SOURCE_OUTSIDE_TOUCH in sources()

        private companion object {
            const val TOUCH_PROBE_ID = "overlay.touch_probe"
            const val SOURCE_OUTSIDE_TOUCH = "outside_touch"
            const val SOURCE_A11Y_EVENTS = "a11y_events"
            const val PROBE_SIZE_PX = 1
            const val BUFFER = 16

            /** 触摸语义的无障碍事件（docs/tech/05 §8.3）。 */
            val USER_TOUCH_EVENT_TYPES: Set<Int> =
                setOf(
                    AccessibilityEvent.TYPE_VIEW_CLICKED,
                    AccessibilityEvent.TYPE_VIEW_SCROLLED,
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                )

            /** 1×1 透明探针：可触摸但不可聚焦，观察窗口外触摸。 */
            fun probeSpec(): OverlaySpec =
                OverlaySpec(
                    x = 0,
                    y = 0,
                    width = PROBE_SIZE_PX,
                    height = PROBE_SIZE_PX,
                    touchable = true,
                    focusable = false,
                    gravity = OverlayGravity.TOP_LEFT,
                    watchOutsideTouch = true,
                )
        }
    }
