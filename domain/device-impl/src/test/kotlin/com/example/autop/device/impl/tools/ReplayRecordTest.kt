package com.example.autop.device.impl.tools

import com.example.autop.agent.ReplayStateSample
import com.example.autop.device.impl.observation.ReplayInput
import com.example.autop.device.impl.observation.buildReplay
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * 可回放记录组装（docs/design/observation-modes-coordinate-actions §5.7）：
 * `buildReplay` 的 execution 判定与 `replayArgsOf` 的 `_hit` 命中回执。
 */
class ReplayRecordTest {
    @Test
    fun `gesture input keeps coordinates and marks execution gesture`() {
        val gesture = gestureJson(10, 20, 300)
        val replay = buildReplay(ReplayInput("tool.tap", args(), gesture), sample())
        assertThat(replay).isNotNull()
        assertThat(replay!!.toolId).isEqualTo("tool.tap")
        assertThat(replay.execution).isEqualTo("gesture")
        assertThat(replay.gesture).isEqualTo(gesture)
        assertThat(replay.postFingerprint).isNull()
        assertThat(replay.pre).isEqualTo(sample())
    }

    @Test
    fun `node action input marks execution node_action without gesture`() {
        val replay = buildReplay(ReplayInput("tool.tap", args(), gesture = null), sample())
        assertThat(replay).isNotNull()
        assertThat(replay!!.execution).isEqualTo("node_action")
        assertThat(replay.gesture).isNull()
        assertThat(replay.postFingerprint).isNull()
    }

    @Test
    fun `replay args include hit receipt`() {
        val hit =
            buildJsonObject {
                put("tag", "btn")
                put("text", "确定")
                put("bounds", buildJsonArrayOf(listOf(0, 0, 100, 100)))
            }
        val result = replayArgsOf(args(), hit)
        assertThat(result.containsKey("id")).isFalse()
        assertThat(result["_hit"]!!.jsonObject["tag"]!!.jsonPrimitive.content).isEqualTo("btn")
        assertThat(result["long"]!!.jsonPrimitive.content).isEqualTo("true")
    }

    @Test
    fun `replay args drop id when hit is unavailable`() {
        val result = replayArgsOf(args(), hit = null)
        assertThat(result.containsKey("id")).isFalse()
        assertThat(result.containsKey("_hit")).isFalse()
        assertThat(result["long"]!!.jsonPrimitive.content).isEqualTo("true")
    }

    @Test
    fun `swipe gesture json carries both points and duration`() {
        val gesture = gestureJson(1, 2, 3, 4, 500)
        for ((key, value) in mapOf("fromX" to "1", "fromY" to "2", "toX" to "3", "toY" to "4", "durationMs" to "500")) {
            assertThat(gesture[key]!!.jsonPrimitive.content).isEqualTo(value)
        }
    }

    private fun sample() =
        ReplayStateSample(
            "com.example.app",
            "Main",
            "fp-1",
            listOf("a", "b"),
            semanticsVersion = 3,
            observeMode = "visual",
        )

    private fun args() =
        buildJsonObject {
            put("id", "n3")
            put("long", true)
        }

    private fun buildJsonArrayOf(values: List<Int>) =
        kotlinx.serialization.json.JsonArray(values.map { kotlinx.serialization.json.JsonPrimitive(it) })
}
