package com.example.autop.device.core.popup

import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PopupFinderTest {
    private val rules =
        PopupRules(
            closeTexts = listOf("取消"),
            closeDescs = listOf("关闭"),
            avoidTexts = listOf("同意并继续"),
        )

    private val clickable = NodeFlags.VISIBLE or NodeFlags.CLICKABLE

    private fun node(
        idx: Int,
        bounds: Rect,
        text: String? = null,
        desc: String? = null,
    ) = NodeSnapshot(
        idx = idx,
        children = emptyList(),
        childIndex = idx,
        text = text,
        contentDesc = desc,
        bounds = bounds,
        flags = clickable,
    )

    private fun snapshot(
        windows: List<WindowSnapshot>,
        nodes: List<NodeSnapshot>,
    ) = ScreenSnapshot(
        capturedAt = 0L,
        windows = windows,
        nodes = nodes,
        screenWidth = 1080,
        screenHeight = 2000,
        rotation = 0,
        keyguardLocked = false,
        truncated = false,
    )

    private val main = WindowSnapshot(1, WindowType.APPLICATION, 0, null, null, Rect(0, 0, 1080, 2000), true, true)
    private val popup = WindowSnapshot(2, WindowType.APPLICATION, 1, null, null, Rect(100, 800, 980, 1600), false, true)

    @Test
    fun `close button identified by description inside the popup is chosen`() {
        val close = node(1, Rect(900, 820, 960, 880), desc = "关闭")
        val pick = PopupFinder.find(snapshot(listOf(main, popup), listOf(close)), rules)
        assertThat(pick?.node?.idx).isEqualTo(1)
        assertThat(pick?.reason).isEqualTo("desc")
    }

    @Test
    fun `close text is used when no description matches`() {
        val cancel = node(1, Rect(200, 1300, 400, 1380), text = "取消")
        val pick = PopupFinder.find(snapshot(listOf(main, popup), listOf(cancel)), rules)
        assertThat(pick?.reason).isEqualTo("text")
    }

    @Test
    fun `buttons outside the popup window are ignored`() {
        val outside = node(1, Rect(10, 10, 100, 100), desc = "关闭")
        assertThat(PopupFinder.find(snapshot(listOf(main, popup), listOf(outside)), rules)).isNull()
    }

    @Test
    fun `no popup window means nothing to dismiss`() {
        val close = node(1, Rect(900, 820, 960, 880), desc = "关闭")
        assertThat(PopupFinder.find(snapshot(listOf(main), listOf(close)), rules)).isNull()
    }

    @Test
    fun `buttons whose label matches an avoided word are never chosen`() {
        val agree = node(1, Rect(200, 1300, 400, 1380), text = "同意并继续")
        val cancel = node(2, Rect(500, 1300, 700, 1380), text = "取消")
        val pick = PopupFinder.find(snapshot(listOf(main, popup), listOf(agree, cancel)), rules)
        assertThat(pick?.node?.idx).isEqualTo(2)
    }
}
