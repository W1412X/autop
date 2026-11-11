/**
 * 注入让路回归（N216 复发收口 → N222 按占位收窄）：
 * 手势只让落点所在的自家窗口、按键只让可聚焦窗口，不冲突的窗口不闪；
 * 注入期新附着窗口继承占位判定、只恢复被占位命中的窗口。
 */
package com.example.autop.device.impl.overlay

import android.content.Context
import android.view.View
import com.example.autop.core.common.Logger
import com.example.autop.device.InjectionFootprint
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
    fun `tap footprint occludes only the window containing the point`() {
        val f = fixture()
        f.show("run_bar", spec(y = 0))
        f.show("panel", spec(y = 500))

        f.registry.addFootprint(InjectionFootprint.TouchPoint(x = 50, y = 50))

        assertThat(f.registry.occludingIds()).containsExactly("run_bar")
        // 不冲突的窗口不闪、仍可触摸。
        assertThat(f.registry.touchableWindowIds()).containsExactly("panel")
        assertThat(f.registry.conflictingTouchableWindows()).isEmpty()
    }

    @Test
    fun `tap footprint leaves every window alone when no window intersects`() {
        val f = fixture()
        f.show("ball", spec(y = 0))
        f.show("panel", spec(y = 500))

        f.registry.addFootprint(InjectionFootprint.TouchPoint(x = 50, y = 300))

        assertThat(f.registry.occludingIds()).isEmpty()
        assertThat(f.registry.touchableWindowIds()).containsExactly("ball", "panel")
    }

    @Test
    fun `margin absorbs near miss`() {
        val f = fixture()
        f.show("run_bar", spec(y = 0))

        // 窗口底边 100，落点 105 在固定安全边距（8dp）内，按冲突处理。
        f.registry.addFootprint(InjectionFootprint.TouchPoint(x = 50, y = 105))

        assertThat(f.registry.occludingIds()).containsExactly("run_bar")
    }

    @Test
    fun `focus footprint occludes only focusable windows`() {
        val f = fixture()
        f.show("run_detail", spec(y = 0, focusable = true))
        f.show("ball", spec(y = 500))

        f.registry.addFootprint(InjectionFootprint.Focus)

        assertThat(f.registry.occludingIds()).containsExactly("run_detail")
        assertThat(f.registry.touchableWindowIds()).containsExactly("ball")
    }

    @Test
    fun `all footprint occludes every touchable window`() {
        val f = fixture()
        f.show("ball", spec(y = 0))
        f.show("panel", spec(y = 500))

        f.registry.addFootprint(InjectionFootprint.All)

        assertThat(f.registry.occludingIds()).containsExactly("ball", "panel")
        assertThat(f.registry.touchableWindowIds()).isEmpty()
    }

    @Test
    fun `window shown during occlusion inherits the footprint verdict`() {
        val f = fixture()
        f.registry.addFootprint(InjectionFootprint.TouchPoint(x = 50, y = 50))

        f.show("late", spec(y = 0))

        assertThat(f.registry.occludingIds()).containsExactly("late")
        assertThat(f.registry.touchableWindowIds()).isEmpty()
    }

    @Test
    fun `restore only brings back windows the footprint hit`() {
        val f = fixture()
        f.show("run_bar", spec(y = 0))
        f.show("panel", spec(y = 500))
        val point = InjectionFootprint.TouchPoint(x = 50, y = 50)
        f.registry.addFootprint(point)

        f.registry.removeFootprint(point)

        assertThat(f.registry.occludingIds()).isEmpty()
        assertThat(f.registry.touchableWindowIds()).containsExactly("run_bar", "panel")
    }

    @Test
    fun `nested footprints keep the union until every footprint is removed`() {
        val f = fixture()
        f.show("run_bar", spec(y = 0))
        f.show("panel", spec(y = 500))
        val top = InjectionFootprint.TouchPoint(x = 50, y = 50)
        val bottom = InjectionFootprint.TouchPoint(x = 50, y = 550)
        f.registry.addFootprint(top)
        f.registry.addFootprint(bottom)

        f.registry.removeFootprint(top)

        assertThat(f.registry.occludingIds()).containsExactly("panel")
        assertThat(f.registry.touchableWindowIds()).containsExactly("run_bar")

        f.registry.removeFootprint(bottom)
        assertThat(f.registry.touchableWindowIds()).containsExactly("run_bar", "panel")
    }

    @Test
    fun `untouchable windows are never occluded nor reported as touchable`() {
        val f = fixture()
        f.show("glow", spec(y = 0, touchable = false))

        f.registry.addFootprint(InjectionFootprint.All)

        assertThat(f.registry.occludingIds()).isEmpty()
        assertThat(f.registry.touchableWindowIds()).isEmpty()
    }

    private fun spec(
        y: Int,
        touchable: Boolean = true,
        focusable: Boolean = false,
    ) = OverlaySpec(
        x = 0,
        y = y,
        width = 100,
        height = 100,
        touchable = touchable,
        focusable = focusable,
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
