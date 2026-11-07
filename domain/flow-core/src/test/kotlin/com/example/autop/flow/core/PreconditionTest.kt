/**
 * 前置断言的验收用例（docs/design/flows-precondition-selfrepair.md P8）。
 *
 * 覆盖：成立则执行动作、不成立则动作未执行、等待策略超时、重试的对象是断言、
 * 交接给智能体时带上条件明细、快速失败不进重试与交接。
 */
package com.example.autop.flow.core

import com.example.autop.agent.RunOutcome
import com.example.autop.flow.Condition
import com.example.autop.flow.FinalAction
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.Handoff
import com.example.autop.flow.OnFail
import com.example.autop.flow.PrePolicy
import com.example.autop.flow.Precondition
import com.example.autop.flow.RetrySpec
import com.example.autop.flow.TextOp
import com.example.autop.flow.ToolNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreconditionTest {
    private val h = FlowTestHarness()

    private fun textPre(
        value: String = "发布页",
        policy: PrePolicy = PrePolicy.FAIL,
        op: TextOp = TextOp.CONTAINS,
    ) = Precondition(cond = Condition.ScreenText(op = op, value = value), policy = policy)

    @Test
    fun `pre holds then the action runs`() {
        h.screenText = "发布页"
        val r = h.run(listOf(h.tool(pre = textPre())))
        assertEquals(RunOutcome.SUCCEEDED, r.status)
        assertEquals(listOf("say:x"), h.toolCalls)
    }

    @Test
    fun `pre fails then the action never runs`() {
        h.screenText = "别的页面"
        val r = h.run(listOf(h.tool(pre = textPre())))
        assertEquals(RunOutcome.FAILED, r.status)
        assertEquals(FlowErrorCodes.PRECONDITION_FAILED, r.errorCode)
        assertTrue("动作不应被执行", h.toolCalls.isEmpty())
    }

    @Test
    fun `pre wait times out and fails without running the action`() {
        h.screenText = "别的页面"
        val r = h.run(listOf(h.tool(pre = textPre(policy = PrePolicy.WAIT))))
        assertEquals(RunOutcome.FAILED, r.status)
        assertEquals(FlowErrorCodes.PRECONDITION_FAILED, r.errorCode)
        assertTrue("动作不应被执行", h.toolCalls.isEmpty())
        assertTrue("等待策略应至少轮询过一次", h.observeCount >= 2)
    }

    @Test
    fun `pre wait continues when the condition becomes true`() {
        h.screenText = "发布页"
        val r = h.run(listOf(h.tool(pre = textPre(policy = PrePolicy.WAIT))))
        assertEquals(RunOutcome.SUCCEEDED, r.status)
        assertEquals(listOf("say:x"), h.toolCalls)
    }

    @Test
    fun `retry re-evaluates the precondition instead of the action`() {
        h.screenText = "别的页面"
        val r =
            h.run(
                listOf(
                    h.tool(
                        pre = textPre(),
                        onFail = OnFail(retry = RetrySpec(max = 3)),
                    ),
                ),
            )
        assertEquals(RunOutcome.FAILED, r.status)
        assertEquals(FlowErrorCodes.PRECONDITION_FAILED, r.errorCode)
        assertTrue("重试的对象是断言，不是动作", h.toolCalls.isEmpty())
    }

    @Test
    fun `pre failure hands off with the condition detail`() {
        h.screenText = "别的页面"
        // 智能体把页面带回正确状态：交接成功后按 after=RETRY 重跑原节点。
        h.onAgentRun = { h.screenText = "发布页" }
        val r =
            h.run(
                listOf(
                    h.tool(
                        pre = textPre(),
                        onFail = OnFail(handoff = Handoff(goal = "回到发布页")),
                    ),
                ),
            )
        assertEquals(1, h.agentGoals.size)
        val goal = h.agentGoals.first()
        assertTrue("交接目标应带流程上下文：$goal", goal.contains("流程「t」"))
        assertTrue("交接目标应带前置明细：$goal", goal.contains("页面文字"))
        val ctx = h.agentContexts.first()
        assertEquals("f1", ctx.flowId)
        assertEquals("t", ctx.nodeId)
        assertTrue("上下文应带失败原因", ctx.failureCode == FlowErrorCodes.PRECONDITION_FAILED)
        assertTrue("上下文应带前置明细", ctx.preconditionDetail?.contains("页面文字") == true)
        assertTrue("屏幕摘要应包不可信信封", ctx.screenSummary?.contains("untrusted") == true)
        assertEquals("交接后仍失败：${r.message}", RunOutcome.SUCCEEDED, r.status)
    }

    @Test
    fun `pre failure with final skip continues the run`() {
        h.screenText = "别的页面"
        val r =
            h.run(
                listOf(
                    h.tool(
                        id = "t1",
                        pre = textPre(),
                        onFail = OnFail(final = FinalAction.SKIP),
                    ),
                    h.tool(id = "t2", text = "y"),
                ),
            )
        assertEquals(RunOutcome.SUCCEEDED, r.status)
        assertEquals(listOf("say:y"), h.toolCalls)
    }

    @Test
    fun `invalid regex fails fast without retry or handoff`() {
        h.screenText = "别的页面"
        val r =
            h.run(
                listOf(
                    h.tool(
                        pre = textPre(value = "[", op = TextOp.REGEX),
                        onFail = OnFail(retry = RetrySpec(max = 3), handoff = Handoff(goal = "去页面")),
                    ),
                ),
            )
        assertEquals(RunOutcome.FAILED, r.status)
        assertEquals(FlowErrorCodes.PRECONDITION_ERROR, r.errorCode)
        assertTrue("条件写错不应唤醒智能体", h.agentGoals.isEmpty())
        assertTrue("条件写错不应执行动作", h.toolCalls.isEmpty())
    }

    @Test
    fun `precondition switch off skips the check`() {
        h.screenText = "别的页面"
        val r =
            h.run(
                listOf(h.tool(pre = textPre())),
                precondition =
                    com.example.autop.core.config
                        .FlowPreconditionSection(enabled = false, pollMs = 50),
            )
        assertEquals(RunOutcome.SUCCEEDED, r.status)
        assertEquals(listOf("say:x"), h.toolCalls)
    }

    @Test
    fun `llm precondition is ignored when the config disallows it`() {
        h.screenText = "别的页面"
        val node: ToolNode =
            h.tool(
                pre =
                    Precondition(
                        cond = Condition.Llm(prompt = "是不是发布页", screen = false),
                        policy = PrePolicy.FAIL,
                    ),
            )
        val r = h.run(listOf(node))
        assertEquals(RunOutcome.SUCCEEDED, r.status)
        assertEquals(listOf("say:x"), h.toolCalls)
        assertTrue(h.logs.any { it.contains("precondition ignored") })
    }
}
