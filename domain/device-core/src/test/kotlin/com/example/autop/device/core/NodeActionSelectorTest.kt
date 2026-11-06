package com.example.autop.device.core

import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NodeActionSelectorTest {
    private val click = NodeQuery(requiredActionId = CLICK)

    @Test
    fun `picks the deepest clickable node under the point`() {
        val snap =
            snapshot(
                node(0, null, listOf(1), Rect(0, 0, 1000, 2000), actions = listOf(CLICK)),
                node(1, 0, emptyList(), Rect(100, 100, 500, 300), actions = listOf(CLICK)),
            )

        val target = NodeActionSelector.select(snap, Point(200, 200), click)

        assertThat(target?.node?.idx).isEqualTo(1)
        assertThat(target?.path).containsExactly(0).inOrder()
    }

    @Test
    fun `later drawn sibling wins over an earlier overlapping sibling`() {
        val snap =
            snapshot(
                node(0, null, listOf(1, 2), Rect(0, 0, 1000, 2000)),
                node(1, 0, emptyList(), Rect(0, 0, 600, 600), actions = listOf(CLICK), drawingOrder = 0),
                node(2, 0, emptyList(), Rect(100, 100, 500, 500), actions = listOf(CLICK), drawingOrder = 1),
            )

        assertThat(NodeActionSelector.select(snap, Point(200, 200), click)?.node?.idx).isEqualTo(2)
    }

    @Test
    fun `node without the required action is not selected`() {
        val snap =
            snapshot(
                node(0, null, listOf(1), Rect(0, 0, 1000, 2000)),
                node(1, 0, emptyList(), Rect(100, 100, 500, 300), flags = VISIBLE_ENABLED or NodeFlags.CLICKABLE),
            )

        assertThat(NodeActionSelector.select(snap, Point(200, 200), click)).isNull()
    }

    @Test
    fun `full screen container above the area limit is rejected`() {
        val snap =
            snapshot(
                node(0, null, emptyList(), Rect(0, 0, 1000, 2000), actions = listOf(CLICK)),
            )

        val limited = click.copy(maxAreaPx = 1_000_000L)

        assertThat(NodeActionSelector.select(snap, Point(500, 500), limited)).isNull()
    }

    @Test
    fun `top window without a candidate blocks windows below it`() {
        val snap =
            snapshot(
                node(0, null, listOf(1), Rect(0, 0, 1000, 2000)),
                node(1, 0, emptyList(), Rect(100, 100, 500, 300), actions = listOf(CLICK)),
                node(2, null, emptyList(), Rect(0, 0, 1000, 2000)),
                windows =
                    listOf(
                        window(WINDOW_PAGE, layer = 0, rootIdx = 0),
                        window(WINDOW_DIALOG, layer = 1, rootIdx = 2),
                    ),
            )

        assertThat(NodeActionSelector.select(snap, Point(200, 200), click)).isNull()
    }

    @Test
    fun `accessibility overlay windows do not block touches`() {
        val snap =
            snapshot(
                node(0, null, listOf(1), Rect(0, 0, 1000, 2000)),
                node(1, 0, emptyList(), Rect(100, 100, 500, 300), actions = listOf(CLICK)),
                node(2, null, emptyList(), Rect(0, 0, 1000, 2000)),
                windows =
                    listOf(
                        window(WINDOW_PAGE, layer = 0, rootIdx = 0),
                        window(WINDOW_DIALOG, layer = 5, rootIdx = 2, type = WindowType.ACCESSIBILITY_OVERLAY),
                    ),
            )

        assertThat(NodeActionSelector.select(snap, Point(200, 200), click)?.node?.idx).isEqualTo(1)
    }

    @Test
    fun `scrollable container is found by flag without an action requirement`() {
        val snap =
            snapshot(
                node(0, null, listOf(1), Rect(0, 0, 1000, 2000)),
                node(1, 0, emptyList(), Rect(0, 300, 1000, 1700), flags = VISIBLE_ENABLED or NodeFlags.SCROLLABLE),
            )

        val target = NodeActionSelector.select(snap, Point(500, 900), NodeQuery(requiredFlag = NodeFlags.SCROLLABLE))

        assertThat(target?.node?.idx).isEqualTo(1)
    }

    private fun snapshot(
        vararg nodes: NodeSnapshot,
        windows: List<WindowSnapshot> = listOf(window(WINDOW_PAGE, layer = 0, rootIdx = 0)),
    ): ScreenSnapshot =
        ScreenSnapshot(
            capturedAt = 0,
            windows = windows,
            nodes = nodes.toList(),
            screenWidth = 1000,
            screenHeight = 2000,
            rotation = 0,
            keyguardLocked = false,
            truncated = false,
        )

    private fun window(
        id: Int,
        layer: Int,
        rootIdx: Int,
        type: WindowType = WindowType.APPLICATION,
    ) = WindowSnapshot(
        windowId = id,
        type = type,
        layer = layer,
        bounds = Rect(0, 0, 1000, 2000),
        active = true,
        focused = true,
        rootIdx = rootIdx,
    )

    private fun node(
        idx: Int,
        parent: Int?,
        children: List<Int>,
        bounds: Rect,
        actions: List<Int> = emptyList(),
        flags: Int = VISIBLE_ENABLED,
        drawingOrder: Int? = null,
    ) = NodeSnapshot(
        idx = idx,
        parent = parent,
        children = children,
        childIndex = 0,
        bounds = bounds,
        flags = flags,
        actions = actions,
        drawingOrder = drawingOrder,
    )

    private companion object {
        const val CLICK = 16
        const val WINDOW_PAGE = 10
        const val WINDOW_DIALOG = 11
        const val VISIBLE_ENABLED = NodeFlags.VISIBLE or NodeFlags.ENABLED
    }
}
