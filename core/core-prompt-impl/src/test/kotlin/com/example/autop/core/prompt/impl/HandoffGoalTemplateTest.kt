package com.example.autop.core.prompt.impl

import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.renderPrompt
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 交接目标模板的验收（docs/design/flows-precondition-selfrepair.md C3/C5）。
 *
 * 隐式交回用这个模板生成目标：变量必须给全，缺一个就渲染失败。
 */
class HandoffGoalTemplateTest {
    private val templates = loadBuiltinTemplates(loadRealPromptFiles())

    @Test
    fun `the template declares the context variables`() {
        assertThat(templates.getValue("flow.recovery.handoff_goal").vars)
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
    }

    @Test
    fun `the template renders with a full variable map`() {
        val template = templates.getValue("flow.recovery.handoff_goal")
        val vars =
            template.vars.associateWith { name ->
                when (name) {
                    "flowName" -> "发布流程"
                    "nodeName" -> "点发布"
                    "nodeId" -> "n3"
                    "failure" -> "前置条件不满足"
                    "expectation" -> "页面文字包含「发布成功」"
                    "anchor" -> "app=com.demo"
                    "precondition" -> "页面文字 contains 「发布页」"
                    "executed" -> "点发布(tool): failed"
                    else -> "<untrusted>页面文字</untrusted>"
                }
            }
        val rendered = renderPrompt(template, vars)
        assertThat(rendered).isInstanceOf(Outcome.Ok::class.java)
        val text = (rendered as Outcome.Ok).value.text
        assertThat(text).contains("发布流程")
        assertThat(text).contains("页面文字包含「发布成功」")
        assertThat(text).contains("untrusted")
    }

    @Test
    fun `the template tells the agent how to submit a node revision`() {
        val body = templates.getValue("flow.recovery.handoff_goal").body
        assertThat(body).contains("tool.patch_flow_node")
    }

    @Test
    fun `a missing variable fails the render`() {
        val template = templates.getValue("flow.recovery.handoff_goal")
        val rendered = renderPrompt(template, mapOf("nodeId" to "n3"))
        assertThat(rendered).isInstanceOf(Outcome.Err::class.java)
    }
}
