/**
 * 注入期输入封锁回归（N216 复发收口，2026-11-10 控制条进度窗吞注入）：
 * 封锁期间全部自家窗口不可触摸、封锁期新附着窗口继承封锁态、解除后恢复。
 */
package com.example.autop.device.impl.overlay

import android.content.Context
import android.view.View
import com.example.autop.core.common.Logger
import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlaySpec
import com.example.autop.device.impl.ServiceHolder
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class OverlayWindowRegistryTest {
    @Test
    fun `block makes all touchable windows untouchable`() {
        val f = fixture()
        f.show("ball", spec(y = 0))
        f.show("run_detail", spec(y = 200))

        f.registry.setInputBlocked(true)

        assertThat(f.registry.touchableWindowIds()).isEmpty()
    }

    @Test
    fun `window shown during block inherits blocked`() {
        val f = fixture()
        f.registry.setInputBlocked(true)

        f.show("run_detail", spec(y = 200))

        assertThat(f.registry.touchableWindowIds()).isEmpty()
    }

    @Test
    fun `unblock restores windows shown before and during block`() {
        val f = fixture()
        f.show("ball", spec(y = 0))
        f.registry.setInputBlocked(true)
        f.show("run_detail", spec(y = 200))

        f.registry.setInputBlocked(false)

        assertThat(f.registry.touchableWindowIds()).containsExactly("ball", "run_detail")
    }

    @Test
    fun `untouchable windows are not reported as touchable`() {
        val f = fixture()
        f.show("glow", spec(y = 0, touchable = false))

        assertThat(f.registry.touchableWindowIds()).isEmpty()
    }

    private fun spec(
        y: Int,
        touchable: Boolean = true,
    ) = OverlaySpec(
        x = 0,
        y = y,
        width = 100,
        height = 100,
        touchable = touchable,
        focusable = false,
        gravity = OverlayGravity.TOP_LEFT,
    )

    private fun fixture(): Fixture {
        val context: Context = RuntimeEnvironment.getApplication()
        return Fixture(OverlayWindowRegistry(context, ServiceHolder(), NoopLogger), context)
    }

    private data class Fixture(
        val registry: OverlayWindowRegistry,
        val context: Context,
    ) {
        /** 注册工厂并附着窗口（真实 attach，touchable 判定要求 window != null）。 */
        fun show(
            id: String,
            spec: OverlaySpec,
        ) {
            registry.register(id, { ctx, _ -> View(ctx) }, OverlayChannel.APPLICATION)
            registry.show(id, spec, OverlayChannel.APPLICATION)
        }
    }

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
}
