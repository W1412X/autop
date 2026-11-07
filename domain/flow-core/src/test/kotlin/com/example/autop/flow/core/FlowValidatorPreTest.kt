/**
 * 前置断言的校验规则用例（docs/design/flows-precondition-selfrepair.md P4）。
 */
package com.example.autop.flow.core

import com.example.autop.agent.ToolAccess
import com.example.autop.flow.Condition
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.IssueSeverity
import com.example.autop.flow.PrePolicy
import com.example.autop.flow.Precondition
import com.example.autop.flow.Target
import com.example.autop.flow.TargetBy
import com.example.autop.flow.ToolNode
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowValidatorPreTest {
    private val catalog =
        object : FlowCatalog {
            override fun toolAccess(name: String): ToolAccess = ToolAccess.READ_SYSTEM

            override fun flow(nameOrId: String): FlowDefinition? = null

            override fun endpointRegistered(name: String): Boolean = true
        }

    private fun validate(steps: List<com.example.autop.flow.Node>) =
        FlowValidator(catalog).validate(FlowDefinition(id = "f1", name = "t", steps = steps))

    private fun tool(
        id: String,
        pre: Precondition? = null,
    ) = ToolNode(id = id, tool = "say", pre = pre)

    @Test
    fun `element condition without a target is an error`() {
        val issues =
            validate(
                listOf(
                    tool(
                        "t",
                        Precondition(
                            cond =
                                Condition.Element(
                                    op = com.example.autop.flow.ElementOp.EXISTS,
                                    target = Target(by = TargetBy.TEXT),
                                ),
                        ),
                    ),
                ),
            )
        assertTrue(issues.any { it.severity == IssueSeverity.ERROR })
    }

    @Test
    fun `screen text condition with an empty value is an error`() {
        val issues =
            validate(
                listOf(
                    tool("t", Precondition(cond = Condition.ScreenText(value = ""))),
                ),
            )
        assertTrue(issues.any { it.severity == IssueSeverity.ERROR })
    }

    @Test
    fun `negative wait timeout is an error`() {
        val issues =
            validate(
                listOf(
                    tool(
                        "t",
                        Precondition(
                            cond = Condition.ScreenText(value = "发布页"),
                            policy = PrePolicy.WAIT,
                            timeoutMs = -1,
                        ),
                    ),
                ),
            )
        assertTrue(issues.any { it.severity == IssueSeverity.ERROR && it.message.contains("timeoutMs") })
    }

    @Test
    fun `llm condition in pre is an error`() {
        val issues =
            validate(
                listOf(
                    tool(
                        "t",
                        Precondition(cond = Condition.Llm(prompt = "是不是发布页", screen = false)),
                    ),
                ),
            )
        assertTrue(issues.any { it.severity == IssueSeverity.ERROR && it.message.contains("llm") })
    }

    @Test
    fun `last ok pointing at a later node is an error`() {
        val issues =
            validate(
                listOf(
                    tool("t1", Precondition(cond = Condition.LastOk(node = "t2"))),
                    tool("t2"),
                ),
            )
        assertTrue(issues.any { it.severity == IssueSeverity.ERROR && it.message.contains("尚未执行") })
    }

    @Test
    fun `last ok pointing at an unknown node is an error`() {
        val issues =
            validate(
                listOf(tool("t1", Precondition(cond = Condition.LastOk(node = "nope")))),
            )
        assertTrue(issues.any { it.severity == IssueSeverity.ERROR && it.message.contains("不存在") })
    }

    @Test
    fun `screen changed pointing at an unknown node is an error`() {
        val issues =
            validate(
                listOf(tool("t1", Precondition(cond = Condition.ScreenChanged(since = "nope")))),
            )
        assertTrue(issues.any { it.severity == IssueSeverity.ERROR && it.message.contains("screenChanged") })
    }

    @Test
    fun `last ok pointing at an earlier node is accepted`() {
        val issues =
            validate(
                listOf(
                    tool("t1"),
                    tool("t2", Precondition(cond = Condition.LastOk(node = "t1"))),
                ),
            )
        assertTrue(issues.none { it.severity == IssueSeverity.ERROR && it.message.contains("pre") })
    }
}
