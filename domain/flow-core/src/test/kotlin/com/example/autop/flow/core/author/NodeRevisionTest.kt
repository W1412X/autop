/**
 * 节点修订转换器的验收用例（docs/design/flows-precondition-selfrepair.md S1/S5）。
 *
 * 修订只换动作与目标：id、失败处理、前置断言、期望效果都要保留。
 */
package com.example.autop.flow.core.author

import com.example.autop.agent.ReplayStateSample
import com.example.autop.agent.ReplayableAction
import com.example.autop.flow.Condition
import com.example.autop.flow.Expect
import com.example.autop.flow.ExpectKind
import com.example.autop.flow.OnFail
import com.example.autop.flow.Precondition
import com.example.autop.flow.RetrySpec
import com.example.autop.flow.Target
import com.example.autop.flow.TargetBy
import com.example.autop.flow.ToolNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NodeRevisionTest {
    private fun original() =
        ToolNode(
            id = "n3",
            name = "点发布",
            tool = "tap",
            args = JsonObject(mapOf("x" to JsonPrimitive(1), "y" to JsonPrimitive(2))),
            target = Target(by = TargetBy.TEXT, value = "旧按钮"),
            expect = Expect(kind = ExpectKind.TEXT, text = "发布成功"),
            onFail = OnFail(retry = RetrySpec(max = 3)),
            pre = Precondition(cond = Condition.ScreenText(value = "发布页")),
        )

    private fun tapStep(text: String) =
        ReplayableAction(
            toolId = "tool.tap",
            replayArgs =
                JsonObject(
                    mapOf(
                        "x" to JsonPrimitive(100),
                        "y" to JsonPrimitive(200),
                        "_hit" to
                            JsonObject(
                                mapOf(
                                    "hit" to
                                        JsonObject(
                                            mapOf(
                                                "text" to JsonPrimitive(text),
                                                "desc" to JsonPrimitive("按钮"),
                                            ),
                                        ),
                                ),
                            ),
                    ),
                ),
            pre = ReplayStateSample(null, null, "fp", emptyList(), observeMode = "a11y"),
            postFingerprint = null,
            execution = "node_action",
        )

    @Test
    fun `revise replaces the target and keeps the semantics`() {
        val node = original()
        val revised = NodeRevision.revise(node, listOf(tapStep("新按钮")))
        assertNotNull(revised)
        requireNotNull(revised)
        assertEquals("n3", revised.id)
        assertEquals("点发布", revised.name)
        assertEquals("新按钮", revised.target?.value)
        assertEquals(ExpectKind.TEXT, revised.expect?.kind)
        assertEquals(3, revised.onFail?.retry?.max)
        assertEquals("发布页", (revised.pre?.cond as? Condition.ScreenText)?.value)
        assertNull("坐标不该写进目标", revised.args["x"])
    }

    @Test
    fun `revise returns null without any action`() {
        assertNull(NodeRevision.revise(original(), emptyList()))
    }

    @Test
    fun `revise only uses the last action`() {
        val steps = listOf(tapStep("第一步"), tapStep("第二步"))
        val revised = NodeRevision.revise(original(), steps)
        assertEquals("第二步", revised?.target?.value)
    }

    @Test
    fun `revise keeps the original target for non tap tools`() {
        val node = original()
        val step =
            tapStep("新按钮").copy(
                toolId = "tool.input_text",
                replayArgs = JsonObject(mapOf("text" to JsonPrimitive("你好"))),
            )
        val revised = NodeRevision.revise(node, listOf(step))
        assertEquals("input_text", revised?.tool)
        assertEquals("你好", (revised?.args?.get("text") as? JsonPrimitive)?.content)
        assertEquals("旧按钮", revised?.target?.value)
    }
}
