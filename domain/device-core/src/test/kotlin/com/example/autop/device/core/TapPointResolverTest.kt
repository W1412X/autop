package com.example.autop.device.core

import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 点击点避让遮挡（docs/tech/05 §3.8、§11，R1/A4）：候选点、遮挡者判定与全盖回退。 */
class TapPointResolverTest {
    @Test
    fun `center returned when nothing covers it`() {
        val snap = singleWindow(occluder = null)
        assertThat(TapPointResolver.Geometry(snap).resolvePoint(target(snap))).isEqualTo(Point(50, 50))
    }

    @Test
    fun `covered center falls back to first free quarter point`() {
        // 遮挡者盖住 (0,0)-(60,60)：中心 (50,50) 与 (25,25) 被盖，(25,75) 自由
        val snap = singleWindow(occluder = Rect(0, 0, 60, 60))
        assertThat(TapPointResolver.Geometry(snap).resolvePoint(target(snap))).isEqualTo(Point(25, 75))
    }

    @Test
    fun `all candidates covered falls back to center`() {
        val snap = singleWindow(occluder = Rect(0, 0, 100, 100))
        assertThat(TapPointResolver.Geometry(snap).resolvePoint(target(snap))).isEqualTo(Point(50, 50))
    }

    @Test
    fun `clickable ancestor and descendant are not occluders`() {
        val snap =
            TestScreens.snapshot(
                windows = listOf(TestScreens.window { rootIdx = 1 }),
                nodes =
                    listOf(
                        TestScreens.node(1) {
                            children = listOf(2)
                            flags = clickable()
                            drawingOrder = 9
                        },
                        TestScreens.node(2) {
                            parent = 1
                            children = listOf(3)
                            bounds = Rect(0, 0, 100, 100)
                        },
                        TestScreens.node(3) {
                            parent = 2
                            bounds = Rect(0, 0, 100, 100)
                            flags = clickable()
                            drawingOrder = 9
                        },
                    ),
            )
        assertThat(TapPointResolver.Geometry(snap).resolvePoint(target(snap))).isEqualTo(Point(50, 50))
    }

    @Test
    fun `sibling with lower drawing order is not an occluder`() {
        val snap =
            TestScreens.snapshot(
                windows = listOf(TestScreens.window { rootIdx = 1 }),
                nodes =
                    listOf(
                        TestScreens.node(1) { children = listOf(2, 3) },
                        TestScreens.node(2) {
                            parent = 1
                            bounds = Rect(0, 0, 100, 100)
                            drawingOrder = 1
                        },
                        TestScreens.node(3) {
                            parent = 1
                            bounds = Rect(0, 0, 100, 100)
                            flags = clickable()
                            drawingOrder = 0
                        },
                    ),
            )
        assertThat(TapPointResolver.Geometry(snap).resolvePoint(target(snap))).isEqualTo(Point(50, 50))
    }

    @Test
    fun `higher layer window node occludes but lower layer does not`() {
        val higher =
            twoWindows(overlayLayer = 1, overlay = Rect(0, 0, 60, 60))
        assertThat(TapPointResolver.Geometry(higher).resolvePoint(target(higher))).isEqualTo(Point(25, 75))
        val lower = twoWindows(overlayLayer = -1, overlay = Rect(0, 0, 60, 60))
        assertThat(TapPointResolver.Geometry(lower).resolvePoint(target(lower))).isEqualTo(Point(50, 50))
    }

    /** 目标 2 号节点（bounds 0,0,100,100），同父兄弟 3 号可选地盖住它。 */
    private fun singleWindow(occluder: Rect?): ScreenSnapshot =
        TestScreens.snapshot(
            windows = listOf(TestScreens.window { rootIdx = 1 }),
            nodes =
                listOf(
                    TestScreens.node(1) { children = listOf(2) + if (occluder != null) listOf(3) else emptyList() },
                    TestScreens.node(2) {
                        parent = 1
                        bounds = Rect(0, 0, 100, 100)
                    },
                    TestScreens.node(3) {
                        parent = 1
                        bounds = occluder ?: Rect(0, 0, 0, 0)
                        flags = clickable()
                        drawingOrder = 5
                    },
                ),
        )

    /** 目标在 1 号窗口（layer 0），2 号窗口（layer [overlayLayer]）里有可点击覆盖层。 */
    private fun twoWindows(
        overlayLayer: Int,
        overlay: Rect,
    ): ScreenSnapshot =
        TestScreens.snapshot(
            windows =
                listOf(
                    TestScreens.window(windowId = 1) {
                        layer = 0
                        rootIdx = 1
                    },
                    TestScreens.window(windowId = 2) {
                        layer = overlayLayer
                        rootIdx = 10
                    },
                ),
            nodes =
                listOf(
                    TestScreens.node(1) { children = listOf(2) },
                    TestScreens.node(2) {
                        parent = 1
                        bounds = Rect(0, 0, 100, 100)
                    },
                    TestScreens.node(10) { children = listOf(11) },
                    TestScreens.node(11) {
                        parent = 10
                        bounds = overlay
                        flags = clickable()
                    },
                ),
        )

    private fun target(snap: ScreenSnapshot): NodeSnapshot = snap.nodes.first { it.idx == 2 }

    private fun clickable(): Int = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.CLICKABLE
}
