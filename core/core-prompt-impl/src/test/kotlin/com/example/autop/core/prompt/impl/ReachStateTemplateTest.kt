package com.example.autop.core.prompt.impl

import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.renderPrompt
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 交回目标（到达状态）模板的验收（G5/G6）：变量必须给全，目标是状态而不是"完成这一步"。 */
class ReachStateTemplateTest {
    private val templates = loadBuiltinTemplates(loadRealPromptFiles())

    @Test
    fun `the template declares the state variables`() {
        assertThat(templates.getValue("flow.recovery.reach_state").vars)
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
    }

    @Test
    fun `the template renders with a full variable map`() {
        val template = templates.getValue("flow.recovery.reach_state")
        val vars =
            template.vars.associateWith { name ->
                when (name) {
                    "flowName" -> "发布流程"
                    "nodeName" -> "点发布"
                    "nodeId" -> "n3"
                    "targetState" -> "页面文字 contains 「已发送」"
                    "instruction" -> "你的任务只是让屏幕到达上述状态。不要执行该步骤本身的操作"
                    "failure" -> "结果契约不成立"
                    "precondition" -> "页面文字 contains 「发布页」"
                    "executed" -> "点发布(tool): failed"
                    "tried" -> "recovery.dismiss_popup"
                    else -> "<untrusted>页面文字</untrusted>"
                }
            }
        val rendered = renderPrompt(template, vars)
        assertThat(rendered).isInstanceOf(Outcome.Ok::class.java)
        val text = (rendered as Outcome.Ok).value.text
        assertThat(text).contains("页面文字 contains 「已发送」")
        assertThat(text).contains("不要执行该步骤本身的操作")
        assertThat(text).doesNotContain("patch_flow_node")
    }

    @Test
    fun `a missing variable fails the render`() {
        val template = templates.getValue("flow.recovery.reach_state")
        val rendered = renderPrompt(template, mapOf("nodeId" to "n3"))
        assertThat(rendered).isInstanceOf(Outcome.Err::class.java)
    }
}
