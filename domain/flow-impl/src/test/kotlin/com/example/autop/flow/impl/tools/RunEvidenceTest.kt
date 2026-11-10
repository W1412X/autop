/**
 * 运行事件 → 证据步骤的验收用例（docs/design/flows-contracts-evidence §3.1）：
 * 动作前屏幕取上一条带观测的结果，命中元素取 `_hit`，无观测步骤 post 为空。
 */
package com.example.autop.flow.impl.tools

import com.example.autop.agent.ObservationRef
import com.example.autop.agent.ReplayStateSample
import com.example.autop.agent.ReplayableAction
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ToolResultEvent
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.llm.Block
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class RunEvidenceTest {
    private val runId = RunId("r1")

    private fun wire(text: String): String =
        """{"screen":{"v":1,"mode":"a11y","windows":[{"app":"com.demo","kids":[
            {"tag":"text","text":"$text","bounds":[0,0,10,10]}]}],"quality":{"nodes":1,"interactive":0}}}"""

    private fun result(
        seq: Long,
        tool: String,
        wire: String? = null,
        hit: JsonObject? = null,
        runId: RunId = this.runId,
    ): ThreadEvent {
        val payload =
            ToolResultEvent(
                callId = "c$seq",
                toolId = tool,
                ok = true,
                blocks = wire?.let { listOf(Block.Text(it)) }.orEmpty(),
                observation =
                    wire?.let {
                        ObservationRef(
                            fingerprint = "fp$seq",
                            packageName = "com.demo",
                            activity = "Main",
                            summaryLine = "s$seq",
                            nodeCount = 1,
                            hasImage = false,
                        )
                    },
                replay =
                    ReplayableAction(
                        toolId = tool,
                        replayArgs = JsonObject(hit?.let { mapOf("_hit" to it) } ?: emptyMap()),
                        pre = ReplayStateSample(null, null, "fp", emptyList(), observeMode = "a11y"),
                        postFingerprint = null,
                        execution = "test",
                    ),
                durationMs = 1,
            )
        return ThreadEvent(threadId = ThreadId("t1"), seq = seq, runId = runId, createdAt = seq, payload = payload)
    }

    @Test
    fun `pairs pre screens and hits from run events`() {
        val hit = buildJsonObject { put("hit", buildJsonObject { put("text", "发送") }) }
        val events =
            listOf(
                result(1, "tool.observe", wire = wire("首页")),
                result(2, "tool.tap", wire = wire("已发送"), hit = hit),
                result(3, "tool.wait"),
                result(4, "tool.observe", wire = wire("别的"), runId = RunId("r2")),
            )

        val steps = evidenceStepsOf(events, runId)

        assertThat(steps).hasSize(3)
        assertThat(steps[0].pre).isNull()
        assertThat(steps[0].post?.nodes).isNotEmpty()
        assertThat(steps[1].pre).isEqualTo(steps[0].post)
        assertThat(steps[1].tool).isEqualTo("tap")
        assertThat(steps[1].hit).isEqualTo(hit)
        assertThat(steps[2].pre).isEqualTo(steps[1].post)
        assertThat(steps[2].post).isNull()
    }
}
