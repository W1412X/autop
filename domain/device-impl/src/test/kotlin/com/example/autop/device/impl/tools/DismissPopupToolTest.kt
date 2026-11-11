/**
 * dismiss_popup 工具（coordinate-node-action-split WP3）：找到关闭按钮后只发坐标手势，
 * 不再走节点分支；执行行只写坐标手势。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.core.common.Outcome
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class DismissPopupToolTest {
    @Test
    fun `close button is clicked with gesture only`() =
        runTest {
            val env = ToolTestEnv()
            val popup = popupScreen()
            env.queue(popup)
            env.queue(popup)
            val tool = env.dismissPopupTool()
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(JsonObject(emptyMap()), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isTrue()
            assertThat(env.gestures.taps).hasSize(1)
            assertThat(env.gestures.nodeActions).isEmpty()
            val text = out.content.joinToString("\n") { it.toString() }
            assertThat(text).contains("执行：坐标手势")
        }

    /** 中央弹窗（面积比 ≈ 0.23，命中 PopupFinder 的弹窗区间）＋“取消”关闭按钮＋填充节点（满足观测充分性）。 */
    private fun popupScreen(): ScreenSnapshot {
        val popup = Rect(140, 700, 940, 1300)
        val root =
            NodeSnapshot(
                idx = 0,
                children = (1..7).toList(),
                childIndex = 0,
                className = "android.widget.FrameLayout",
                bounds = popup,
                flags = NodeFlags.VISIBLE or NodeFlags.ENABLED,
                packageName = "com.example.app",
            )
        return ScreenSnapshot(
            capturedAt = 0L,
            windows =
                listOf(
                    WindowSnapshot(
                        windowId = 1,
                        type = WindowType.APPLICATION,
                        layer = 0,
                        packageName = "com.example.app",
                        title = "弹窗",
                        bounds = popup,
                        active = true,
                        focused = true,
                        rootIdx = 0,
                    ),
                ),
            nodes = listOf(root, cancelButton()) + fillers(),
            foregroundPackage = "com.example.app",
            activity = "com.example.app.MainActivity",
            screenWidth = ENV_SCREEN_WIDTH,
            screenHeight = ENV_SCREEN_HEIGHT,
            rotation = 0,
            keyguardLocked = false,
            contentArea = ENV_FULL,
            truncated = false,
        )
    }

    private fun cancelButton(): NodeSnapshot =
        NodeSnapshot(
            idx = 1,
            parent = 0,
            children = emptyList(),
            childIndex = 0,
            className = "android.widget.Button",
            text = "取消",
            bounds = Rect(300, 1150, 500, 1250),
            flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.CLICKABLE,
            packageName = "com.example.app",
        )

    /** 观测充分性填充（非交互文本节点，不进弹窗候选）。 */
    private fun fillers(): List<NodeSnapshot> =
        (2..7).map { index ->
            NodeSnapshot(
                idx = index,
                parent = 0,
                children = emptyList(),
                childIndex = index - 1,
                className = "android.widget.TextView",
                text = "提示$index",
                bounds = Rect(100, 100 * index, 500, 100 * index + 50),
                flags = NodeFlags.VISIBLE or NodeFlags.ENABLED,
                packageName = "com.example.app",
            )
        }

    private fun okOf(outcome: Outcome<PreparedAction>): PreparedAction =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected ok, was ${outcome.error.code}")
        }
}
