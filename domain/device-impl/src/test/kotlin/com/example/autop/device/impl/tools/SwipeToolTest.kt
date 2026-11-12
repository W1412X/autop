/**
 * swipe（docs/design/tool_optimize/02 §6、06 §1.3）：duration_ms 超过
 * `device.scroll.swipeMaxDurationMs` 返回参数错误；正常时发一次滑动手势。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Outcome
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
class SwipeToolTest {
    @Test
    fun `duration above the configured max is rejected`() =
        runTest {
            val env = ToolTestEnv()
            env.queue(a11yScreenOf(emptyList()))
            val tool = env.swipeTool()
            val ctx = env.ctx()

            val got = tool.prepare(args("""{"x1":100,"y1":100,"x2":100,"y2":900,"duration_ms":3001}"""), ctx)

            val error = (got as Outcome.Err).error
            assertThat(error.code).isEqualTo(CommonErrorCodes.INVALID_ARGUMENT)
            assertThat(error.message).contains("3000")
            assertThat(env.gestures.swipes).isEmpty()
        }

    @Test
    fun `perform sends one swipe gesture`() =
        runTest {
            val env = ToolTestEnv()
            env.queue(a11yScreenOf(emptyList()))
            val tool = env.swipeTool()
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(args("""{"x1":100,"y1":100,"x2":100,"y2":900,"duration_ms":500}"""), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isTrue()
            assertThat(env.gestures.swipes).hasSize(1)
        }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun okOf(outcome: Outcome<PreparedAction>): PreparedAction =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected ok, was ${outcome.error.code}")
        }
}
