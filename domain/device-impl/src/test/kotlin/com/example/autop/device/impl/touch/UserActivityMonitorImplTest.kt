/**
 * 触摸检测（agent-harness-overhaul WP7.3）：自身动作窗口登记取并集——
 * 动作中/等待稳定窗口内的触摸不算用户操作，超出宽限才算。
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.autop.device.impl.touch

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.example.autop.core.common.DeviceQuirks
import com.example.autop.core.common.Logger
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.device.OverlayPermission
import com.example.autop.device.ScreenLease
import com.example.autop.device.impl.A11yEventBus
import com.example.autop.device.impl.A11yEventLite
import com.example.autop.device.impl.ServiceHolder
import com.example.autop.device.impl.overlay.OverlayChannelResolver
import com.example.autop.device.impl.overlay.OverlayWindowManager
import com.example.autop.device.impl.overlay.OverlayWindowRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class UserActivityMonitorImplTest {
    @Test
    fun `touch during action is self`() =
        runTest {
            val f = fixture()
            // GestureController 动作前登记（WP7.3）：start..start+selfActionMaxMs。
            f.monitor.markSelfActionWindow(f.clock.nowMillis(), f.clock.nowMillis() + SELF_ACTION_MAX_MS)

            f.bus.emit(userClick())

            assertThat(f.monitor.lastUserTouchAt()).isNull()
        }

    @Test
    fun `touch during stabilize window is self`() =
        runTest {
            val f = fixture()
            // awaitStable 等待前后各登记一次（WP7.3）：窗口取并集。
            f.monitor.markSelfActionWindow(f.clock.nowMillis(), f.clock.nowMillis() + SELF_ACTION_MAX_MS)
            f.clock.advanceBy(STABILIZE_WAIT_MS)
            f.monitor.markSelfActionWindow(0L, f.clock.nowMillis() + SELF_ACTION_GRACE_MS)

            f.bus.emit(userClick())

            assertThat(f.monitor.lastUserTouchAt()).isNull()
        }

    @Test
    fun `touch after grace is user`() =
        runTest {
            val f = fixture()
            f.monitor.markSelfActionWindow(f.clock.nowMillis(), f.clock.nowMillis() + SELF_ACTION_GRACE_MS)
            f.clock.advanceBy(SELF_ACTION_GRACE_MS + AFTER_GRACE_MS)

            f.bus.emit(userClick())

            assertThat(f.monitor.lastUserTouchAt()).isEqualTo(f.clock.nowMillis())
        }

    private fun fixture(): Fixture {
        val context: Context = RuntimeEnvironment.getApplication()
        val provider = FakeConfigProvider()
        val clock = FakeClock(0L)
        val logger = NoopLogger
        val dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher())
        val holder = ServiceHolder()
        val registry = OverlayWindowRegistry(context, holder, logger)
        val resolver = OverlayChannelResolver(holder, NoOverlayPermission, provider, NoQuirks)
        val overlay = OverlayWindowManager(resolver, registry, logger, dispatchers)
        val bus = A11yEventBus(provider)
        val monitor = UserActivityMonitorImpl(overlay, bus, NoLease, provider, clock, logger, dispatchers)
        return Fixture(monitor, bus, clock)
    }

    private fun userClick(): A11yEventLite =
        A11yEventLite(
            type = AccessibilityEvent.TYPE_VIEW_CLICKED,
            packageName = "com.example.app",
            className = null,
            time = 0L,
            isSelfOrigin = false,
        )

    private data class Fixture(
        val monitor: UserActivityMonitorImpl,
        val bus: A11yEventBus,
        val clock: FakeClock,
    )

    private object NoopLogger : Logger {
        override fun d(
            tag: String,
            event: String,
            vararg kv: Pair<String, Any?>,
        ) = Unit

        override fun i(
            tag: String,
            event: String,
            vararg kv: Pair<String, Any?>,
        ) = Unit

        override fun w(
            tag: String,
            event: String,
            vararg kv: Pair<String, Any?>,
            error: Throwable?,
        ) = Unit

        override fun e(
            tag: String,
            event: String,
            vararg kv: Pair<String, Any?>,
            error: Throwable?,
        ) = Unit
    }

    private object NoOverlayPermission : OverlayPermission {
        override fun granted(): Boolean = false

        override val changes: Flow<Boolean> = MutableStateFlow(false)
    }

    private object NoQuirks : DeviceQuirks {
        override fun value(key: String): String? = null
    }

    private object NoLease : ScreenLease {
        override suspend fun <T> withLease(
            owner: String,
            block: suspend () -> T,
        ): T = block()

        override val holder: StateFlow<String?> = MutableStateFlow(null)
    }

    private companion object {
        const val SELF_ACTION_MAX_MS = 15_000L
        const val SELF_ACTION_GRACE_MS = 400L
        const val STABILIZE_WAIT_MS = 2_000L
        const val AFTER_GRACE_MS = 1L
    }
}
