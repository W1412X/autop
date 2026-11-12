/**
 * tap_node 节点工具（coordinate-node-action-split §8 错误表）：各错误分支如实报错、
 * 成功只走节点动作不发手势、策略主体取实时节点、回放写成 tool.tap 坐标。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ToolContext
import com.example.autop.core.common.Outcome
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.NodeActionOutcome
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
import com.example.autop.device.core.SerializedScreen
import com.example.autop.device.impl.ObserveSelection
import com.example.autop.device.impl.RecordedObservation
import com.example.autop.device.impl.deviceHistory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class TapNodeToolTest {
    @Test
    fun `no observation returns mode_unavailable`() =
        runTest {
            val env = ToolTestEnv()
            val got = prepare(env, id = 1)

            assertThat(errCodeOf(got)).isEqualTo(DeviceErrorCodes.MODE_UNAVAILABLE)
        }

    @Test
    fun `non a11y observation returns mode_unavailable`() =
        runTest {
            val env = ToolTestEnv()
            recordVisual(env)
            val got = prepare(env, id = 1)

            assertThat(errCodeOf(got)).isEqualTo(DeviceErrorCodes.MODE_UNAVAILABLE)
        }

    @Test
    fun `unknown id returns node_not_found`() =
        runTest {
            val env = ToolTestEnv()
            recordA11y(env)
            val got = prepare(env, id = 99)

            assertThat(errCodeOf(got)).isEqualTo(DeviceErrorCodes.NODE_NOT_FOUND)
        }

    @Test
    fun `node without click act returns invalid_argument`() =
        runTest {
            val env = ToolTestEnv()
            recordA11y(env, act = emptyList())
            val got = prepare(env, id = 1)

            assertThat(errCodeOf(got)).isEqualTo(DeviceErrorCodes.INVALID_ARGUMENT)
        }

    @Test
    fun `empty live tree returns app_tree_empty`() =
        runTest {
            val env = ToolTestEnv()
            recordA11y(env)
            env.queue(a11yScreenOf(emptyList()))
            val got = prepare(env, id = 1)

            assertThat(errCodeOf(got)).isEqualTo(DeviceErrorCodes.APP_TREE_EMPTY)
        }

    @Test
    fun `moved node returns node_stale`() =
        runTest {
            val env = ToolTestEnv()
            recordA11y(env)
            env.queue(a11yScreenOf(listOf(clickableNode(1, "确定", Rect(300, 600, 700, 700)))))
            val got = prepare(env, id = 1)

            assertThat(errCodeOf(got)).isEqualTo(DeviceErrorCodes.NODE_STALE)
        }

    @Test
    fun `rejected action returns node_rejected with at hint`() =
        runTest {
            val env = ToolTestEnv()
            recordA11y(env)
            env.queue(a11yScreenOf(listOf(clickableNode(1, "确定", LIVE_BOUNDS))))
            env.gestures.pathOutcome = NodeActionOutcome.REJECTED
            val ctx = env.ctx()
            val prepared = okOf(prepare(env, ctx, id = 1))

            val out = env.tapNodeTool().perform(prepared, ctx)

            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(DeviceErrorCodes.NODE_REJECTED)
            assertThat(out.content.first().toString()).contains("at=")
            assertThat(env.gestures.taps).isEmpty()
        }

    @Test
    fun `success runs node action only and records tool tap replay`() =
        runTest {
            val env = ToolTestEnv()
            recordA11y(env)
            env.queue(a11yScreenOf(listOf(clickableNode(1, "确定", LIVE_BOUNDS))))
            env.queue(a11yScreenOf(listOf(clickableNode(1, "确定", LIVE_BOUNDS))))
            val ctx = env.ctx()
            val prepared = okOf(prepare(env, ctx, id = 1))
            // 策略主体取实时节点（实时文本）。
            assertThat(prepared.subject.targetText).isEqualTo("确定")

            val out = env.tapNodeTool().perform(prepared, ctx)

            assertThat(out.ok).isTrue()
            assertThat(env.gestures.taps).isEmpty()
            assertThat(env.gestures.nodeActions).hasSize(1)
            val text = out.content.joinToString("\n") { it.toString() }
            val seen = text + out.trace.joinToString(" ")
            assertThat(text).contains("已对节点 #1 执行 ACTION_CLICK")
            assertThat(seen).contains("执行：无障碍节点动作 ACTION_CLICK")
            // 回放写成等价 tool.tap 坐标（id 不进回放）。
            val replay = out.replay
            assertThat(replay?.toolId).isEqualTo("tool.tap")
            val args = replay?.replayArgs ?: JsonObject(emptyMap())
            assertThat(args.keys).containsAtLeast("x", "y", "_hit")
            assertThat(args.keys).doesNotContain("id")
        }

    private suspend fun prepare(
        env: ToolTestEnv,
        id: Int,
    ): Outcome<PreparedAction> = prepare(env, env.ctx(), id)

    private suspend fun prepare(
        env: ToolTestEnv,
        ctx: ToolContext,
        id: Int,
    ): Outcome<PreparedAction> {
        val tool = env.tapNodeTool()
        val args = Json.parseToJsonElement("""{"id":$id}""").jsonObject
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

    /** 记录一次带 id 的 a11y 观测（id=1、ref=1）。 */
    private fun recordA11y(
        env: ToolTestEnv,
        act: List<String> = listOf("click"),
    ) {
        val snap = a11yScreenOf(listOf(clickableNode(1, "确定", LIVE_BOUNDS)))
        val node =
            ScreenNode(
                tag = "btn",
                text = "确定",
                act = act,
                bounds = Rect(0, 0, 100, 50),
                at = Point(50, 25),
                id = 1,
                ref = 1,
            )
        record(env, ObserveMode.A11Y, snap, listOf(node))
    }

    /** 记录一次 visual 观测（无 id）。 */
    private fun recordVisual(env: ToolTestEnv) {
        val snap = a11yScreenOf(listOf(clickableNode(1, "确定", LIVE_BOUNDS)))
        val node = ScreenNode(tag = "btn", text = "确定", act = listOf("click"), bounds = Rect(0, 0, 100, 50))
        record(env, ObserveMode.VISUAL, snap, listOf(node))
    }

    private fun record(
        env: ToolTestEnv,
        mode: ObserveMode,
        snap: ScreenSnapshot,
        nodes: List<ScreenNode>,
    ) {
        val model =
            ScreenModel(
                mode = mode,
                windows = listOf(ScreenWindow(app = "com.example.app", kids = nodes)),
                quality = ScreenQuality(nodes = nodes.size, interactive = nodes.size),
            )
        val observation =
            Observation(
                text = "{}",
                mode = ObservationMode.FULL,
                observeMode = mode,
                fingerprint = "fp",
                packageName = "com.example.app",
                activity = "com.example.app.MainActivity",
                summaryLine = "app",
                interactiveCount = nodes.size,
                nodeCount = nodes.size,
                semanticPoor = false,
            )
        env.ctx().deviceHistory().last =
            RecordedObservation(
                mode = mode,
                selection = ObserveSelection.Explicit(mode),
                screen = SerializedScreen(observation, model),
                snapshot = snap,
                frame = null,
            )
    }

    private companion object {
        val LIVE_BOUNDS: Rect = Rect(300, 200, 700, 300)
    }
}
