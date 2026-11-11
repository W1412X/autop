/**
 * 轨迹转换（coordinate-node-action-split §5.8）：节点工具产生的回放记录
 * （toolId=tool.tap / tool.scroll，带 `_hit`）能转成对应流程节点；节点工具本体不进流程。
 */
package com.example.autop.flow.core.author

import com.example.autop.agent.ReplayStateSample
import com.example.autop.agent.ReplayableAction
import com.example.autop.flow.ToolNode
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class TrajectoryConverterTest {
    @Test
    fun `node tool replay record converts to tap flow node`() {
        val step =
            ReplayableAction(
                toolId = "tool.tap",
                replayArgs =
                    Json
                        .parseToJsonElement(
                            """{"x":500,"y":250,"_hit":{"tag":"btn","text":"确定","bounds":[300,200,700,300]}}""",
                        ).jsonObject,
                pre = sample(),
                postFingerprint = "fp",
                execution = "node_action",
            )

        val flow =
            TrajectoryConverter.convert(
                flowId = "f1",
                name = "节点工具回放",
                description = "tap_node 的回放记录",
                steps = listOf(step),
                settleMinMs = 300,
                settleMaxMs = 800,
            )

        val node = flow.steps.single()
        assertThat(node).isInstanceOf(ToolNode::class.java)
        assertThat((node as ToolNode).tool).isEqualTo("tap")
    }

    @Test
    fun `scroll node replay record converts to scroll flow node`() {
        val step =
            ReplayableAction(
                toolId = "tool.scroll",
                replayArgs =
                    Json
                        .parseToJsonElement(
                            """{"direction":"down","steps":1,"x":500,"y":500,"_hit":{"tag":"list"}}""",
                        ).jsonObject,
                pre = sample(),
                postFingerprint = "fp",
                execution = "node_action",
            )

        val flow =
            TrajectoryConverter.convert(
                flowId = "f1",
                name = "滚动回放",
                description = "scroll_node 的回放记录",
                steps = listOf(step),
                settleMinMs = 300,
                settleMaxMs = 800,
            )

        val node = flow.steps.single()
        assertThat((node as ToolNode).tool).isEqualTo("scroll")
    }

    private fun sample(): ReplayStateSample =
        ReplayStateSample(
            packageName = "com.example.app",
            activity = "com.example.app.MainActivity",
            fingerprint = "fp",
            keys = emptyList(),
            observeMode = "a11y",
        )
}
