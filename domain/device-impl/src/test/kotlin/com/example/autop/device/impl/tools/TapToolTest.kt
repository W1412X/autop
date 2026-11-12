/**
 * tap 坐标工具（coordinate-node-action-split WP3）：只发手势（shell → 无障碍），
 * 不再有节点分支；执行行只写坐标手势。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.core.common.Outcome
import com.example.autop.device.Rect
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
class TapToolTest {
    @Test
    fun `perform sends only gesture and reports gesture line`() =
        runTest {
            val env = ToolTestEnv()
            val screen = a11yScreenOf(listOf(clickableNode(1, "确定", Rect(300, 200, 700, 300))))
            env.queue(screen)
            env.queue(screen)
            val tool = env.tapTool()
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(args(), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isTrue()
            assertThat(env.gestures.taps).hasSize(1)
            assertThat(env.gestures.nodeActions).isEmpty()
            val text = out.content.joinToString("\n") { it.toString() }
            val seen = text + out.trace.joinToString(" ")
            assertThat(seen).contains("执行：坐标手势")
            assertThat(seen).doesNotContain("无障碍节点动作")
        }

    @Test
    fun `inject failure returns error without fallback`() =
        runTest {
            val env = ToolTestEnv()
            env.gestures.tapOk = false
            env.queue(a11yScreenOf(listOf(clickableNode(1, "确定", Rect(300, 200, 700, 300)))))
            val tool = env.tapTool()
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(args(), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isFalse()
            assertThat(env.gestures.taps).hasSize(1)
            assertThat(env.gestures.nodeActions).isEmpty()
        }

    private fun args(): JsonObject =
        Json
            .parseToJsonElement("""{"x":500,"y":250}""")
            .jsonObject

    private fun okOf(outcome: Outcome<PreparedAction>): PreparedAction =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected ok, was ${outcome.error.code}")
        }
}
