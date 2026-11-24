@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.autop.device.impl.bgdisplay.route

import com.example.autop.core.common.Clock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BackgroundUiStabilizerTest {
    @Test
    fun `unchanged fingerprint waits for first event timeout`() =
        runTest {
            val stabilizer = BackgroundUiStabilizer(SchedulerClock(testScheduler))
            var samples = 0

            val waited =
                stabilizer.awaitStable(quietMs = 300, maxWaitMs = 3000, firstEventTimeoutMs = 800) {
                    samples++
                    "same"
                }

            assertThat(waited.settled).isTrue()
            assertThat(waited.waitedMs).isEqualTo(800)
            assertThat(samples).isEqualTo(17)
        }

    @Test
    fun `changed fingerprint must remain quiet before stable`() =
        runTest {
            val stabilizer = BackgroundUiStabilizer(SchedulerClock(testScheduler))
            var samples = 0

            val waited =
                stabilizer.awaitStable(quietMs = 300, maxWaitMs = 3000, firstEventTimeoutMs = 800) {
                    samples++
                    if (samples == 1) "old" else "new"
                }

            assertThat(waited.settled).isTrue()
            assertThat(waited.waitedMs).isEqualTo(350)
            assertThat(samples).isEqualTo(8)
        }

    @Test
    fun `continuously changing fingerprint times out without real screen events`() =
        runTest {
            val stabilizer = BackgroundUiStabilizer(SchedulerClock(testScheduler))
            var samples = 0

            val waited =
                stabilizer.awaitStable(quietMs = 300, maxWaitMs = 3000, firstEventTimeoutMs = 800) {
                    "frame-${samples++}"
                }

            assertThat(waited.settled).isFalse()
            assertThat(waited.waitedMs).isEqualTo(3000)
        }

    private class SchedulerClock(
        private val scheduler: TestCoroutineScheduler,
    ) : Clock {
        override fun nowMillis(): Long = scheduler.currentTime
    }
}
