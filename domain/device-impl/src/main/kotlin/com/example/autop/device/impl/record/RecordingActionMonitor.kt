/**
 * 录制期间的用户操作采集（docs/design/flows-recording-recovery.md V3）。
 *
 * - [RecordingGate] 只有在录制时才打开；无障碍服务据此采集事件源节点的文字与边界。
 * - 密码框的文字永远不进入记录（[UserAction.text] 为 null）。
 * - 同一节点的同类操作在 `flow.recorder.minGapMs` 内重复出现时丢弃（抖动）。
 */
package com.example.autop.device.impl.record

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.FlowConfig
import com.example.autop.device.UserAction
import com.example.autop.device.UserActionMonitor
import com.example.autop.device.UserActionType
import com.example.autop.device.impl.A11yEventBus
import com.example.autop.device.impl.A11yEventLite
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 录制开关：无障碍服务只在打开时采集事件源节点（避免常态开销）。 */
@Singleton
public class RecordingGate
    @Inject
    constructor() {
        @Volatile
        public var active: Boolean = false
    }

/** 用户操作采集实现。 */
@Singleton
public class RecordingActionMonitor
    @Inject
    constructor(
        private val bus: A11yEventBus,
        private val gate: RecordingGate,
        private val configProvider: ConfigProvider,
        private val dispatchers: DispatcherProvider,
        @ApplicationContext context: Context,
    ) : UserActionMonitor {
        private val out =
            MutableSharedFlow<UserAction>(
                extraBufferCapacity = OUT_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        override val actions: SharedFlow<UserAction> = out.asSharedFlow()

        private val ownPackage: String = context.packageName
        private var scope: CoroutineScope? = null
        private var job: Job? = null
        private var last: UserAction? = null

        /** 最近一次自家界面交互（事件时间戳，与 [A11yEventLite.time] 同源）。 */
        private val ownInteraction = OwnInteractionWindow()

        override fun start() {
            if (job != null) return
            gate.active = true
            val s = CoroutineScope(SupervisorJob() + dispatchers.default)
            scope = s
            job =
                s.launch {
                    bus.events.collect { event -> map(event)?.let { emit(it) } }
                }
        }

        override fun stop() {
            gate.active = false
            job?.cancel()
            job = null
            scope?.cancel()
            scope = null
            last = null
        }

        private fun emit(action: UserAction) {
            val prev = last
            val minGap = configProvider.current()[FlowConfig.RECORDER].minGapMs
            if (prev != null && isJitter(prev, action, minGap)) return
            last = action
            out.tryEmit(action)
        }

        /** 同一节点的同类操作在间隔内重复出现，视为抖动。 */
        private fun isJitter(
            prev: UserAction,
            action: UserAction,
            minGap: Long,
        ): Boolean = prev.type == action.type && prev.bounds == action.bounds && action.time - prev.time < minGap

        /** 把底层事件转成录制操作；与录制无关的事件返回 null。 */
        internal fun map(event: A11yEventLite): UserAction? {
            if (event.isSelfOrigin || event.packageName == ownPackage) {
                noteOwnInteraction(event)
                return null
            }
            val type = scrollTypeOf(event) ?: typeOf(event.type) ?: return null
            if (type == UserActionType.WINDOW_CHANGED && inOwnInteractionWindow(event.time)) return null
            val src = event.source
            // 密码框：文字一律丢弃，TEXT_CHANGED 直接忽略（方案 V3.4）。
            if (src?.isPassword == true && type == UserActionType.TEXT_CHANGED) return null
            return UserAction(
                type = type,
                packageName = event.packageName,
                className = src?.className ?: event.className,
                viewId = src?.viewId,
                text = if (src?.isPassword == true) null else src?.text,
                contentDescription = if (src?.isPassword == true) null else src?.contentDescription,
                bounds = src?.bounds,
                isPassword = src?.isPassword == true,
                time = event.time,
            )
        }

        /**
         * 记下自家界面的交互（点击、长按、窗口出现或消失）：操作悬浮球、录制面板、拾取层会让
         * 目标应用随后补发窗口切换事件，那不是用户在目标应用里的操作，由 [inOwnInteractionWindow] 过滤。
         * 只认这几类事件，避免悬浮球动画产生的内容变化事件把过滤窗口一直续上。
         */
        private fun noteOwnInteraction(event: A11yEventLite) {
            if (event.type in OWN_INTERACTION_EVENTS) ownInteraction.note(event.time)
        }

        private fun inOwnInteractionWindow(time: Long): Boolean {
            val suppressMs = configProvider.current()[FlowConfig.RECORDER].overlaySuppressMs
            return ownInteraction.covers(time, suppressMs)
        }

        /** 滚动方向：增量为正表示内容向上移动（用户向下滚），零增量不记录。 */
        private fun scrollTypeOf(event: A11yEventLite): UserActionType? {
            if (event.type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return null
            return when {
                (event.scrollDeltaY ?: 0) > 0 -> UserActionType.SCROLL_FORWARD
                (event.scrollDeltaY ?: 0) < 0 -> UserActionType.SCROLL_BACKWARD
                else -> null
            }
        }

        private fun typeOf(eventType: Int): UserActionType? =
            when (eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> UserActionType.CLICK
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> UserActionType.LONG_CLICK
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> UserActionType.TEXT_CHANGED
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> UserActionType.WINDOW_CHANGED
                else -> null
            }

        private companion object {
            const val OUT_BUFFER = 64

            /** 视为“用户在操作自家界面”的事件类型。 */
            val OWN_INTERACTION_EVENTS: Set<Int> =
                setOf(
                    AccessibilityEvent.TYPE_VIEW_CLICKED,
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                )
        }
    }
