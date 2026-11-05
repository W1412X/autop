package com.example.autop.feature.overlay.panel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PanelLogicTest {
    @Test
    fun `panel opens on the ball side when right side fits`() {
        // 球 x=100、球 52、面板 300、间距 8：右侧 360+300<=720 → 球右。
        val x = panelXOf(ballX = 100, ballSizePx = 52, panelWidthPx = 300, screenWidthPx = 720, gapPx = 8)
        assertThat(x).isEqualTo(160)
    }

    @Test
    fun `panel opens on the left when right side does not fit`() {
        // 球贴右缘 x=600：右侧放不下 → 球左（600-300-8=292）。
        val x = panelXOf(ballX = 600, ballSizePx = 52, panelWidthPx = 300, screenWidthPx = 720, gapPx = 8)
        assertThat(x).isEqualTo(292)
    }

    @Test
    fun `panel x is clamped inside the screen`() {
        // 球在屏外（x=-100）：右贴球侧为 -40，钳到 0。
        val x = panelXOf(ballX = -100, ballSizePx = 52, panelWidthPx = 300, screenWidthPx = 720, gapPx = 8)
        assertThat(x).isEqualTo(0)
    }

    @Test
    fun `panel y never goes above the top`() {
        assertThat(panelYOf(-30)).isEqualTo(0)
        assertThat(panelYOf(120)).isEqualTo(120)
    }
}
