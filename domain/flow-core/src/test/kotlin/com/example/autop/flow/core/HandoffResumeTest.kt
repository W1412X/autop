/**
 * 交回智能体后的接续语义验收（G5/G6）：交回目标是"到达状态"，引擎复核目标状态是唯一标准。
 *
 * 前置失败：智能体只负责到达前置状态，动作由流程自己执行（不代做本步）。
 * 结果失败：复核结果契约，成立直接进下一节点，不再执行动作（不重复发送）。
 * 复核失败（子 Run 报成功也算）：恢复失败，收尾错误码为 flow.state_not_reached。
 */
package com.example.autop.flow.core

import com.example.autop.agent.RunOutcome
import com.example.autop.core.config.FlowRecoverySection
import com.example.autop.flow.AfterRecovery
import com.example.autop.flow.Condition
import com.example.autop.flow.Expect
import com.example.autop.flow.ExpectKind
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.Handoff
import com.example.autop.flow.OnFail
import com.example.autop.flow.Precondition
import com.example.autop.flow.TextOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffResumeTest {
    private val h = FlowTestHarness()

    /** 空 goal = 隐式交回路径（目标文本由端口按 reach_state 模板生成）。 */
    private val handoff = OnFail(handoff = Handoff(goal = ""))

    private fun recovery() = FlowRecoverySection(verifyTimeoutMs = 200, verifyPollMs = 50)

    private fun textExpect(value: String) = Expect(kind = ExpectKind.TEXT, text = value)

    @Test
    fun `result failure resumes at the next node without re-running the action`() {
        h.toolFailuresLeft = 1
        h.onAgentRun = { h.screenText = "已发送" }
        val r =
            h.run(
                listOf(h.tool(onFail = handoff, expect = textExpect("已发送"))),
                recovery = recovery(),
            )
        assertEquals("交接后应恢复成功：${r.message}", RunOutcome.SUCCEEDED, r.status)
        assertEquals("动作只应执行失败的那一次，不应重做", 1, h.toolCalls.size)
        val ctx = h.agentContexts.first()
        assertEquals("目标状态应来自结果契约", "页面文字包含「已发送」", ctx.targetState)
        assertTrue("结果失败允许完成本步", ctx.instruction?.contains("完成") == true)
    }

    @Test
    fun `agent claims success but the result contract is unmet then recovery fails`() {
        h.toolFailuresLeft = 1
        val r =
            h.run(
                listOf(h.tool(onFail = handoff, expect = textExpect("已发送"))),
                recovery = recovery(),
            )
        assertEquals(RunOutcome.FAILED, r.status)
        assertEquals(FlowErrorCodes.STATE_NOT_REACHED, r.errorCode)
        assertEquals("复核失败也不应重做动作", 1, h.toolCalls.size)
    }

    @Test
    fun `pre failure resumes with the flow running the action itself`() {
        h.screenText = "别的页面"
        h.onAgentRun = { h.screenText = "发布页" }
        val r =
            h.run(
                listOf(
                    h.tool(
                        pre = Precondition(cond = Condition.ScreenText(op = TextOp.CONTAINS, value = "发布页")),
                        onFail = handoff,
                    ),
                ),
                recovery = recovery(),
            )
        assertEquals("交接后应恢复成功：${r.message}", RunOutcome.SUCCEEDED, r.status)
        assertEquals("动作应由流程自己执行一次", listOf("say:x"), h.toolCalls)
        val ctx = h.agentContexts.first()
        assertTrue("目标状态应来自前置契约：${ctx.targetState}", ctx.targetState?.contains("发布页") == true)
        assertTrue("前置失败不允许代做本步", ctx.instruction?.contains("不要执行") == true)
    }

    @Test
    fun `agent claims success but the precondition is still unmet then recovery fails`() {
        h.screenText = "别的页面"
        val r =
            h.run(
                listOf(
                    h.tool(
                        pre = Precondition(cond = Condition.ScreenText(op = TextOp.CONTAINS, value = "发布页")),
                        onFail = handoff,
                    ),
                ),
                recovery = recovery(),
            )
        assertEquals(RunOutcome.FAILED, r.status)
        assertEquals(FlowErrorCodes.PRECONDITION_FAILED, r.errorCode)
        assertTrue("复核失败不应执行动作", h.toolCalls.isEmpty())
    }

    @Test
    fun `after continue keeps its meaning and advances without the action`() {
        h.toolFailuresLeft = 1
        val r =
            h.run(
                listOf(
                    h.tool(
                        onFail = OnFail(handoff = Handoff(goal = ""), after = AfterRecovery.CONTINUE),
                        expect = textExpect("已发送"),
                    ),
                ),
                recovery = recovery(),
            )
        assertEquals(RunOutcome.SUCCEEDED, r.status)
        assertEquals(1, h.toolCalls.size)
    }
}
