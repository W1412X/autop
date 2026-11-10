@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.autop.device.impl

import android.view.accessibility.AccessibilityEvent
import com.example.autop.core.common.Clock
import com.example.autop.core.testing.FakeConfigProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 等待稳定三段算法（docs/tech/05 §2.5、§11，R1/A2）：
 * 无事件即返 / 延迟跳转不误判 / 持续动画指纹收敛与不收敛。
 */
class UiStabilizerImplTest {
    @Test
    fun `no event returns zero without sampling fingerprint`() =
        runTest {
            val bus = A11yEventBus(FakeConfigProvider())
            val stabilizer = UiStabilizerImpl(bus, FakeConfigProvider(), SchedulerClock(testScheduler))
            var samples = 0
            val waited =
                stabilizer.awaitStable(quietMs = QUIET_MS, maxWaitMs = MAX_WAIT_MS, firstEventTimeoutMs = FIRST_MS) {
                    samples++
                    "fp"
                }
            assertThat(waited.waitedMs).isEqualTo(NO_WAIT)
            assertThat(samples).isEqualTo(0)
            // 一直没事件要等满 firstEventTimeoutMs 才认定界面没变化
            assertThat(testScheduler.currentTime).isAtLeast(FIRST_MS)
        }

    @Test
    fun `delayed navigation is not mistaken for stable`() =
        runTest {
            val bus = A11yEventBus(FakeConfigProvider())
            val stabilizer = UiStabilizerImpl(bus, FakeConfigProvider(), SchedulerClock(testScheduler))
            backgroundScope.launch {
                delay(EVENT_ONE_MS)
                bus.emit(event())
                delay(EVENT_TWO_MS - EVENT_ONE_MS)
                bus.emit(event())
            }
            var samples = 0
            val waited =
                stabilizer.awaitStable(quietMs = QUIET_MS, maxWaitMs = MAX_WAIT_MS, firstEventTimeoutMs = FIRST_MS) {
                    samples++
                    "fp"
                }
            // 事件 500/700ms 才到：等到 700 + quietMs=300 → 1000ms，而不是首事件超时就返回
            assertThat(waited.waitedMs).isEqualTo(EVENT_TWO_MS + QUIET_MS)
            assertThat(samples).isEqualTo(0)
        }

    @Test
    fun `continuous animation converges by equal fingerprints`() =
        runTest {
            val bus = A11yEventBus(FakeConfigProvider())
            val stabilizer = UiStabilizerImpl(bus, FakeConfigProvider(), SchedulerClock(testScheduler))
            backgroundScope.launch {
                while (true) {
                    delay(EVENT_TICK_MS)
                    bus.emit(event())
                }
            }
            val waited =
                stabilizer.awaitStable(quietMs = QUIET_MS, maxWaitMs = MAX_WAIT_MS, firstEventTimeoutMs = FIRST_MS) {
                    "fp-same"
                }
            // 事件不停 → 到 maxWaitMs 后连续两次指纹相同即稳定（间隔 pollMs）
            assertThat(waited.waitedMs).isEqualTo(MAX_WAIT_MS + POLL_MS)
        }

    @Test
    fun `fingerprint still changing returns maxWaitMs`() =
        runTest {
            val bus = A11yEventBus(FakeConfigProvider())
            val stabilizer = UiStabilizerImpl(bus, FakeConfigProvider(), SchedulerClock(testScheduler))
            backgroundScope.launch {
                while (true) {
                    delay(EVENT_TICK_MS)
                    bus.emit(event())
                }
            }
            var n = 0
            val waited =
                stabilizer.awaitStable(quietMs = QUIET_MS, maxWaitMs = MAX_WAIT_MS, firstEventTimeoutMs = FIRST_MS) {
                    "fp-${n++}"
                }
            assertThat(waited.waitedMs).isEqualTo(MAX_WAIT_MS)
        }

    private fun event(): A11yEventLite =
        A11yEventLite(
            type = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            packageName = "com.example.app",
            className = null,
            time = 0L,
            isSelfOrigin = false,
        )

    /** 跟随测试虚拟时间的时钟：delay 推进即时间推进，断言确定。 */
    private class SchedulerClock(
        private val scheduler: TestCoroutineScheduler,
    ) : Clock {
        override fun nowMillis(): Long = scheduler.currentTime
    }

    private companion object {
        const val QUIET_MS = 300L
        const val MAX_WAIT_MS = 3000L
        const val FIRST_MS = 800L
        const val POLL_MS = 50L
        const val EVENT_ONE_MS = 500L
        const val EVENT_TWO_MS = 700L
        const val EVENT_TICK_MS = 100L
        const val NO_WAIT = 0L
    }
}
