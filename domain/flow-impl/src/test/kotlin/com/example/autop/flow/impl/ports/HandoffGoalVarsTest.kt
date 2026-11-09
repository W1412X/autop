/**
 * 交接上下文的验收用例（docs/design/flows-precondition-selfrepair.md C5）。
 *
 * 模板缺变量会渲染失败：变量集合必须与模板声明一致。
 */
package com.example.autop.flow.impl.ports

import com.example.autop.flow.core.AgentContext
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HandoffGoalVarsTest {
    private val context =
        AgentContext(
            flowId = "f1",
            flowName = "发布流程",
            nodeId = "n3",
            nodeName = "点发布",
            nodeType = "tool",
            targetHint = "text=发布",
            expectation = "页面文字包含「发布成功」",
            anchorHint = "app=com.demo",
            preconditionDetail = "页面文字 contains 「发布页」",
            failureCode = "flow.precondition_failed",
            failureMessage = "前置条件不满足",
            executedSteps = listOf("点发布(tool): failed"),
            screenSummary = "<untrusted source=\"flow\">页面文字</untrusted>",
            triedRecoveries = listOf("recovery.wait_stable"),
        )

    @Test
    fun `vars cover every variable of the handoff goal template`() {
        val vars = handoffGoalVars("n3", context)
        assertThat(vars.keys)
            .containsExactly(
                "nodeId",
                "nodeName",
                "flowName",
                "failure",
                "expectation",
                "anchor",
                "precondition",
                "executed",
                "screen",
            )
        assertThat(vars["flowName"]).isEqualTo("发布流程")
        assertThat(vars["expectation"]).contains("发布成功")
        assertThat(vars["screen"]).contains("untrusted")
    }

    @Test
    fun `missing context still fills every variable`() {
        val vars = handoffGoalVars("n3", null)
        assertThat(vars.keys).hasSize(9)
        assertThat(vars["nodeName"]).isEqualTo("n3")
        assertThat(vars["flowName"]).isEmpty()
        assertThat(vars["screen"]).isEmpty()
    }

    @Test
    fun `vars cover every variable of the reach state template`() {
        val ctx =
            context.copy(
                targetState = "页面文字 contains 「已发送」",
                instruction = "不要执行该步骤本身的操作",
            )
        val vars = reachStateVars("n3", ctx)
        assertThat(vars.keys)
            .containsExactly(
                "nodeId",
                "nodeName",
                "flowName",
                "targetState",
                "instruction",
                "failure",
                "precondition",
                "executed",
                "tried",
                "screen",
            )
        assertThat(vars["targetState"]).contains("已发送")
        assertThat(vars["instruction"]).contains("不要执行")
        assertThat(vars["tried"]).contains("recovery.wait_stable")
    }

    @Test
    fun `reach state vars fill empty strings without context`() {
        val vars = reachStateVars("n3", null)
        assertThat(vars.keys).hasSize(10)
        assertThat(vars["targetState"]).isEmpty()
        assertThat(vars["instruction"]).isEmpty()
    }
}
