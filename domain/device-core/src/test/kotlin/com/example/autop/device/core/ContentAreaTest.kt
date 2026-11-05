/**
 * 内容区推导（N204）：系统装饰条带裁剪、方向判定与退化回退。
 */
package com.example.autop.device.core

import com.example.autop.device.Rect
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContentAreaTest {
    private fun decoration(
        title: String,
        bounds: Rect,
    ): WindowSnapshot =
        WindowSnapshot(
            windowId = 1,
            type = WindowType.SYSTEM,
            layer = 10,
            packageName = "com.android.systemui",
            title = title,
            bounds = bounds,
            active = false,
            focused = false,
            rootIdx = null,
        )

    @Test
    fun `status and navigation bars are cropped from portrait screen`() {
        val windows =
            listOf(
                decoration("StatusBar", Rect(0, 0, 1080, 120)),
                decoration("NavigationBar", Rect(0, 2280, 1080, 2400)),
            )
        assertThat(ContentArea.of(1080, 2400, windows)).isEqualTo(Rect(0, 120, 1080, 2280))
    }

    @Test
    fun `side navigation bar is cropped in landscape`() {
        val windows = listOf(decoration("NavigationBar", Rect(0, 0, 96, 1080)))
        assertThat(ContentArea.of(2400, 1080, windows)).isEqualTo(Rect(96, 0, 2400, 1080))
    }

    @Test
    fun `notification shade and dialogs are kept`() {
        val windows =
            listOf(
                decoration("NotificationShade", Rect(0, 0, 1080, 1200)),
                decoration("VolumeDialog", Rect(200, 800, 880, 1400)),
            )
        assertThat(ContentArea.of(1080, 2400, windows)).isEqualTo(Rect(0, 0, 1080, 2400))
    }

    @Test
    fun `decoration title matching is case insensitive`() {
        val windows = listOf(decoration("statusbar", Rect(0, 0, 1080, 120)))
        assertThat(ContentArea.of(1080, 2400, windows)).isEqualTo(Rect(0, 120, 1080, 2400))
    }

    @Test
    fun `degenerate decoration falls back to full screen`() {
        val windows = listOf(decoration("StatusBar", Rect(0, 0, 1080, 2400)))
        assertThat(ContentArea.of(1080, 2400, windows)).isEqualTo(Rect(0, 0, 1080, 2400))
    }

    @Test
    fun `half expanded decoration strip is not cropped`() {
        // N204 审查：通知面板展开中间态等“半膨胀”条带（厚度超过屏 1/4）跳过不裁。
        val windows = listOf(decoration("StatusBar", Rect(0, 0, 1080, 800)))
        assertThat(ContentArea.of(1080, 2400, windows)).isEqualTo(Rect(0, 0, 1080, 2400))
    }

    @Test
    fun `application windows are not decoration`() {
        val app =
            WindowSnapshot(
                windowId = 2,
                type = WindowType.APPLICATION,
                layer = 1,
                packageName = "com.example.app",
                title = "StatusBarGame",
                bounds = Rect(0, 0, 1080, 120),
                active = true,
                focused = true,
                rootIdx = 1,
            )
        assertThat(app.isSystemDecoration()).isFalse()
        assertThat(ContentArea.of(1080, 2400, listOf(app))).isEqualTo(Rect(0, 0, 1080, 2400))
    }
}
