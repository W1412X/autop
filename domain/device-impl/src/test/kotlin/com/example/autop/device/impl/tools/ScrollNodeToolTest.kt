/**
 * scroll_node 节点工具（coordinate-node-action-split §10）：方向动作缺失、被拒、
 * 到头停止、steps 上限；只走节点动作不补手势。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ToolContext
import com.example.autop.core.common.Outcome
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.NodeActionOutcome
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Observation
import com.example.autop.device.ObservationMode
import com.example.autop.device.ObserveMode
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenNode
import com.example.autop.device.ScreenQuality
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.ScreenWindow
import com.example.autop.device.core.ScrollDirection
import com.example.autop.device.core.SerializedScreen
import com.example.autop.device.impl.ObserveSelection
import com.example.autop.device.impl.RecordedObservation
import com.example.autop.device.impl.deviceHistory
import com.example.autop.device.impl.nodeaction.NodeActionIds
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ScrollNodeToolTest {
    @Test
    fun `node without direction scroll action returns not_scrollable`() =
        runTest {
            val env = ToolTestEnv()
            recordA11y(env, liveActions = emptyList())
            val got = prepare(env, steps = 1)

            assertThat(errCodeOf(got)).isEqualTo(DeviceErrorCodes.NOT_SCROLLABLE)
        }

    @Test
    fun `rejected scroll returns node_rejected`() =
        runTest {
            val env = ToolTestEnv()
            recordA11y(env)
            env.gestures.pathOutcome = NodeActionOutcome.REJECTED
            val ctx = env.ctx()
            val prepared = okOf(prepare(env, ctx, steps = 1))

            val out = env.scrollNodeTool().perform(prepared, ctx)

            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(DeviceErrorCodes.NODE_REJECTED)
            assertThat(env.gestures.swipes).isEmpty()
        }

    @Test
    fun `runs all steps even when screen does not move`() =
        runTest {
            val env = ToolTestEnv()
            recordA11y(env)
            env.queue(liveScreen())
            val ctx = env.ctx()
            val prepared = okOf(prepare(env, ctx, steps = 3))

            val out = env.scrollNodeTool().perform(prepared, ctx)

            assertThat(out.ok).isTrue()
            // tool_optimize 02 §5：不做“到头”判断，steps 连续执行。
            assertThat(env.gestures.nodeActions).hasSize(3)
            assertThat(env.gestures.swipes).isEmpty()
        }

    @Test
    fun `steps are capped at maxSteps`() =
        runTest {
            val env = ToolTestEnv()
            env.reader.animate = true
            recordA11y(env)
            env.queue(liveScreen())
            val ctx = env.ctx()
            val prepared = okOf(prepare(env, ctx, steps = 99))

            val out = env.scrollNodeTool().perform(prepared, ctx)

            assertThat(out.ok).isTrue()
            // 默认 maxSteps=5：连续移动时执行满 5 步。
            assertThat(env.gestures.nodeActions).hasSize(5)
        }

    private suspend fun prepare(
        env: ToolTestEnv,
        steps: Int,
    ): Outcome<PreparedAction> = prepare(env, env.ctx(), steps)

    private suspend fun prepare(
        env: ToolTestEnv,
        ctx: ToolContext,
        steps: Int,
    ): Outcome<PreparedAction> {
        val tool = env.scrollNodeTool()
        val args =
            Json
                .parseToJsonElement("""{"id":1,"direction":"down","steps":$steps}""")
                .jsonObject
        return tool.prepare(args, ctx)
    }

    private fun errCodeOf(outcome: Outcome<PreparedAction>): String =
        when (outcome) {
            is Outcome.Ok -> error("expected error")
            is Outcome.Err -> outcome.error.code
        }

    private fun okOf(outcome: Outcome<PreparedAction>): PreparedAction =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected ok, was ${outcome.error.code}")
        }

    private fun liveScreen(liveActions: List<Int> = scrollActions()): ScreenSnapshot =
        a11yScreenOf(listOf(scrollableNode(1, LIVE_BOUNDS, liveActions)))

    private fun scrollActions(): List<Int> = listOf(NodeActionIds.scrollActionOf(ScrollDirection.DOWN))

    private fun scrollableNode(
        idx: Int,
        bounds: Rect,
        actions: List<Int>,
    ): NodeSnapshot =
        NodeSnapshot(
            idx = idx,
            parent = 0,
            children = emptyList(),
            childIndex = 0,
            className = "android.widget.ScrollView",
            text = "列表",
            bounds = bounds,
            flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.SCROLLABLE,
            actions = actions,
            packageName = "com.example.app",
        )

    /** 记录一次带 id 的 a11y 滚动节点观测（id=1、ref=1），并把实时树排队给解析器。 */
    private fun recordA11y(
        env: ToolTestEnv,
        liveActions: List<Int> = scrollActions(),
    ) {
        val snap = liveScreen(liveActions)
        env.queue(snap)
        val node =
            ScreenNode(
                tag = "list",
                text = "列表",
                act = listOf("scroll"),
                bounds = Rect(0, 0, 100, 100),
                at = Point(50, 50),
                id = 1,
                ref = 1,
            )
        val model =
            ScreenModel(
                mode = ObserveMode.A11Y,
                windows = listOf(ScreenWindow(app = "com.example.app", kids = listOf(node))),
                quality = ScreenQuality(nodes = 1, interactive = 1),
            )
        val observation =
            Observation(
                text = "{}",
                mode = ObservationMode.FULL,
                observeMode = ObserveMode.A11Y,
                fingerprint = "fp",
                packageName = "com.example.app",
                activity = "com.example.app.MainActivity",
                summaryLine = "app",
                interactiveCount = 1,
                nodeCount = 1,
                semanticPoor = false,
            )
        env.ctx().deviceHistory().last =
            RecordedObservation(
                mode = ObserveMode.A11Y,
                selection = ObserveSelection.Explicit(ObserveMode.A11Y),
                screen = SerializedScreen(observation, model),
                snapshot = snap,
                frame = null,
            )
    }

    private companion object {
        val LIVE_BOUNDS: Rect = Rect(100, 300, 980, 1500)
    }
}
