/**
 * 契约继承与结果契约核验的验收用例（docs/design/flows-contracts-evidence §7.1/§7.2）：
 * 生效前置 = 显式 pre，否则继承上一个工具节点的结果契约（wait/log/set 透明，
 * if/switch 合流处不继承，screenChanged/none 不继承）；`expect.cond` 优先于 kind。
 */
package com.example.autop.flow.core

import com.example.autop.agent.RunOutcome
import com.example.autop.core.config.FlowContractSection
import com.example.autop.core.config.FlowRecoverySection
import com.example.autop.flow.Condition
import com.example.autop.flow.Expect
import com.example.autop.flow.ExpectKind
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.IfNode
import com.example.autop.flow.LogNode
import com.example.autop.flow.Precondition
import com.example.autop.flow.Target
import com.example.autop.flow.TargetBy
import com.example.autop.flow.TextOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractInheritanceTest {
    private val h = FlowTestHarness()

    private fun textExpect(value: String) = Expect(kind = ExpectKind.TEXT, text = value)

    private fun flipBefore(
        nodeId: String,
        text: String,
    ) {
        h.onBeforeNode = { if (it == nodeId) h.screenText = text }
    }

    @Test
    fun `inherited pre comes from the previous tool node result contract`() {
        h.screenText = "已发送"
        val r =
            h.run(
                listOf(
                    h.tool(id = "t1", text = "x", expect = textExpect("已发送")),
                    h.tool(id = "t2", text = "y"),
                ),
            )
        assertEquals(RunOutcome.SUCCEEDED, r.status)
        assertEquals(listOf("say:x", "say:y"), h.toolCalls)
    }

    @Test
    fun `inherited pre blocks the next node when unmet`() {
        h.screenText = "已发送"
        flipBefore("t2", "别的页面")
        val r =
            h.run(
                listOf(
                    h.tool(id = "t1", text = "x", expect = textExpect("已发送")),
                    h.tool(id = "t2", text = "y"),
                ),
            )
        assertEquals(RunOutcome.FAILED, r.status)
        assertEquals(FlowErrorCodes.PRECONDITION_FAILED, r.errorCode)
        assertTrue("失败说明应交代继承来源：${r.message}", r.message.orEmpty().contains("继承"))
        assertEquals("继承不成立时动作不应执行", listOf("say:x"), h.toolCalls)
    }

    @Test
    fun `wait and log nodes do not block inheritance`() {
        h.screenText = "已发送"
        flipBefore("t2", "别的页面")
        val r =
            h.run(
                listOf(
                    h.tool(id = "t1", text = "x", expect = textExpect("已发送")),
                    LogNode(id = "l", message = "记一笔"),
                    h.tool(id = "t2", text = "y"),
                ),
            )
        assertEquals(RunOutcome.FAILED, r.status)
        assertEquals(FlowErrorCodes.PRECONDITION_FAILED, r.errorCode)
    }

    @Test
    fun `node right after an if does not inherit`() {
        h.screenText = "已发送"
        flipBefore("t2", "别的页面")
        val r =
            h.run(
                listOf(
                    h.tool(id = "t1", text = "x", expect = textExpect("已发送")),
                    IfNode(id = "if1", cond = Condition.ScreenText(op = TextOp.CONTAINS, value = "绝不会出现")),
                    h.tool(id = "t2", text = "y"),
                ),
            )
        assertEquals("合流处不继承，t2 应照常执行：${r.message}", RunOutcome.SUCCEEDED, r.status)
        assertEquals(listOf("say:x", "say:y"), h.toolCalls)
    }

    @Test
    fun `screenChanged and none contracts do not inherit`() {
        h.screenText = "别的页面"
        h.onToolCall = { h.screenText = "已发送" }
        flipBefore("t2", "回来的页面")
        val r =
            h.run(
                listOf(
                    h.tool(id = "t1", text = "x", expect = Expect(kind = ExpectKind.SCREEN_CHANGED)),
                    h.tool(id = "t2", text = "y"),
                ),
            )
        assertEquals(RunOutcome.SUCCEEDED, r.status)
        assertEquals(listOf("say:x", "say:y"), h.toolCalls)
    }

    @Test
    fun `inherit switch off skips the check`() {
        h.screenText = "已发送"
        flipBefore("t2", "别的页面")
        val r =
            h.run(
                listOf(
                    h.tool(id = "t1", text = "x", expect = textExpect("已发送")),
                    h.tool(id = "t2", text = "y"),
                ),
                contract = FlowContractSection(inherit = false),
            )
        assertEquals(RunOutcome.SUCCEEDED, r.status)
        assertEquals(listOf("say:x", "say:y"), h.toolCalls)
    }

    @Test
    fun `explicit pre replaces the inherited contract`() {
        h.screenText = "已发送"
        flipBefore("t2", "别的页面")
        val r =
            h.run(
                listOf(
                    h.tool(id = "t1", text = "x", expect = textExpect("已发送")),
                    h.tool(
                        id = "t2",
                        text = "y",
                        pre = Precondition(cond = Condition.ScreenText(op = TextOp.CONTAINS, value = "别的页面")),
                    ),
                ),
            )
        assertEquals("显式 pre 成立即通过，不再叠加继承：${r.message}", RunOutcome.SUCCEEDED, r.status)
        assertEquals(listOf("say:x", "say:y"), h.toolCalls)
    }

    @Test
    fun `expect cond is checked at runtime and beats kind`() {
        h.screenText = "别的页面"
        h.onToolCall = { h.screenText = "已发送" }
        val node =
            h.tool(
                id = "t1",
                text = "x",
                expect =
                    Expect(
                        kind = ExpectKind.NONE,
                        cond = Condition.ScreenText(op = TextOp.CONTAINS, value = "已发送"),
                    ),
            )
        val ok = h.run(listOf(node))
        assertEquals("cond 成立即通过：${ok.message}", RunOutcome.SUCCEEDED, ok.status)

        val h2 = FlowTestHarness()
        h2.screenText = "别的页面"
        val bad =
            h2.run(
                listOf(
                    h2.tool(
                        id = "t1",
                        text = "x",
                        expect =
                            Expect(
                                kind = ExpectKind.NONE,
                                cond = Condition.Element(target = Target(by = TargetBy.TEXT, value = "没出现的按钮")),
                            ),
                    ),
                ),
                recovery = FlowRecoverySection(verifyTimeoutMs = 200, verifyPollMs = 50),
            )
        assertEquals(RunOutcome.FAILED, bad.status)
        assertEquals(FlowErrorCodes.CONTRACT_UNMET, bad.errorCode)
    }
}
