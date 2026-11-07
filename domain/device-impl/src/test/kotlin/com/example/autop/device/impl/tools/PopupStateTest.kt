/**
 * 弹窗关闭校验（docs/design/observation-modes-coordinate-actions §5.9）：
 * 只有拿到完整节点树且确实找不到弹窗才报已关闭；空树/读取失败一律无法确认。
 */
package com.example.autop.device.impl.tools

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType
import com.example.autop.device.core.popup.PopupRules
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PopupStateTest {
    private val rules = PopupRules(closeTexts = listOf("关闭"))

    @Test
    fun `read failure is unknown instead of closed`() {
        val state = popupStateOf(Outcome.Err(AppError("device.service_unavailable", "无障碍服务未连接")), rules)
        assertThat(state).isEqualTo(PopupState.UNKNOWN)
    }

    @Test
    fun `empty tree is unknown even though no popup is found`() {
        assertThat(popupStateOf(Outcome.Ok(snapshot(nodes = emptyList())), rules)).isEqualTo(PopupState.UNKNOWN)
    }

    @Test
    fun `remaining close button means the popup is still there`() {
        val state =
            popupStateOf(
                Outcome.Ok(snapshot(nodes = listOf(closeButton()))),
                rules,
            )
        assertThat(state).isEqualTo(PopupState.STILL_THERE)
    }

    @Test
    fun `full screen window without popup button means closed`() {
        val state =
            popupStateOf(
                Outcome.Ok(
                    snapshot(
                        windows = listOf(window(Rect(0, 0, 1000, 1000))),
                        nodes = listOf(closeButton()),
                    ),
                ),
                rules,
            )
        assertThat(state).isEqualTo(PopupState.CLOSED)
    }

    private fun snapshot(
        windows: List<WindowSnapshot> = listOf(window(Rect(100, 100, 900, 900))),
        nodes: List<NodeSnapshot>,
    ): ScreenSnapshot =
        ScreenSnapshot(
            capturedAt = 1_700_000_000_000,
            windows = windows,
            nodes = nodes,
            foregroundPackage = "com.example.app",
            activity = "com.example.app.MainActivity",
            screenWidth = 1000,
            screenHeight = 1000,
            rotation = 0,
            keyguardLocked = false,
            truncated = false,
        )

    private fun window(bounds: Rect): WindowSnapshot =
        WindowSnapshot(
            windowId = 1,
            type = WindowType.APPLICATION,
            layer = 0,
            packageName = "com.example.app",
            title = "弹窗",
            bounds = bounds,
            active = true,
            focused = true,
            rootIdx = 1,
        )

    private fun closeButton(): NodeSnapshot =
        NodeSnapshot(
            idx = 2,
            parent = 1,
            children = emptyList(),
            childIndex = 0,
            className = "android.widget.Button",
            text = "关闭",
            bounds = Rect(400, 800, 600, 900),
            flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.CLICKABLE,
        )
}
