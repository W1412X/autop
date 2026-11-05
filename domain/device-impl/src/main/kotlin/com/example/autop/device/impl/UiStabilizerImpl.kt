/**
 * 等待界面稳定（docs/tech/05 §2.5，R1/A2）：① 先等第一个界面变化事件（没有事件即认为界面没变化，
 * 立即返回 0）；② 收到事件后等静默 [UiStabilizer.awaitStable] 的 quietMs；③ 到 maxWaitMs 上限时
 * 连续两次指纹相同也算稳定。超时返回已等待时长，不是错误。
 */
package com.example.autop.device.impl

import android.view.accessibility.AccessibilityEvent
import com.example.autop.core.common.Clock
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.UiStabilizer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** [UiStabilizer] 实现：订阅 [A11yEventBus] 的界面变化事件并轮询静默时长。 */
@Singleton
public class UiStabilizerImpl
    @Inject
    constructor(
        private val eventBus: A11yEventBus,
        private val configProvider: ConfigProvider,
        private val clock: Clock,
    ) : UiStabilizer {
        /** 三段等待（docs/tech/05 §2.5）：等首事件 → 等静默 → 指纹收敛判定。 */
        override suspend fun awaitStable(
            quietMs: Long,
            maxWaitMs: Long,
            firstEventTimeoutMs: Long,
            sampleFingerprint: suspend () -> String?,
        ): Long =
            coroutineScope {
                val spec =
                    WaitSpec(
                        start = clock.nowMillis(),
                        quietMs = quietMs,
                        maxWaitMs = maxWaitMs,
                        firstEventTimeoutMs = firstEventTimeoutMs,
                        pollMs = configProvider.current()[DeviceConfig.STABILIZE].pollMs,
                    )
                var lastEventAt: Long? = null
                // UNDISPATCHED：先完成订阅再进入等待，避免动作刚发生时的事件漏收。
                val watcher =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        eventBus.events.collect { event ->
                            if (event.type in STABILIZE_EVENTS) lastEventAt = clock.nowMillis()
                        }
                    }
                try {
                    waitPhases(spec, { lastEventAt }, sampleFingerprint)
                } finally {
                    watcher.cancel()
                }
            }

        /** ① 无事件返回 0；② 静默返回已等待时长；③ 指纹相同返回已等待时长，否则返回 maxWaitMs。 */
        private suspend fun waitPhases(
            spec: WaitSpec,
            lastEventAt: () -> Long?,
            sampleFingerprint: suspend () -> String?,
        ): Long =
            when {
                !awaitFirstEvent(spec, lastEventAt) -> NO_WAIT
                awaitQuiet(spec, lastEventAt) -> clock.nowMillis() - spec.start
                fingerprintStable(spec.pollMs, sampleFingerprint) -> clock.nowMillis() - spec.start
                else -> spec.maxWaitMs
            }

        /** ① 等第一个事件，最多 firstEventTimeoutMs；一直没有事件返回 false。 */
        private suspend fun awaitFirstEvent(
            spec: WaitSpec,
            lastEventAt: () -> Long?,
        ): Boolean {
            while (true) {
                if (lastEventAt() != null) return true
                if (clock.nowMillis() - spec.start >= spec.firstEventTimeoutMs) return false
                delay(spec.pollMs)
            }
        }

        /** ② 事件静默 quietMs 返回 true；到 maxWaitMs 上限仍不静默返回 false。 */
        private suspend fun awaitQuiet(
            spec: WaitSpec,
            lastEventAt: () -> Long?,
        ): Boolean {
            while (true) {
                val now = clock.nowMillis()
                if (now - (lastEventAt() ?: spec.start) >= spec.quietMs) return true
                if (now - spec.start >= spec.maxWaitMs) return false
                delay(spec.pollMs)
            }
        }

        /** ③ 连续两次指纹相同（间隔 pollMs）算稳定；取样失败（null）不算。 */
        private suspend fun fingerprintStable(
            pollMs: Long,
            sampleFingerprint: suspend () -> String?,
        ): Boolean {
            val first = sampleFingerprint()
            delay(pollMs)
            return first != null && first == sampleFingerprint()
        }

        private data class WaitSpec(
            val start: Long,
            val quietMs: Long,
            val maxWaitMs: Long,
            val firstEventTimeoutMs: Long,
            val pollMs: Long,
        )

        private companion object {
            /** 没有事件时的返回值：界面没有变化，实际等待 0。 */
            const val NO_WAIT = 0L

            val STABILIZE_EVENTS =
                setOf(
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                    AccessibilityEvent.TYPE_VIEW_SCROLLED,
                )
        }
    }
