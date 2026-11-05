package com.example.autop.core.prompt

import com.example.autop.core.common.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PromptRendererTest {
    private fun template(
        vars: Set<String> = setOf("a", "b"),
        optionalVars: Set<String> = setOf("b"),
        body: String = "你好 {{a}}{{#if b}}，{{b}}{{/if}}！",
    ) = PromptTemplate(
        id = "test.tpl",
        version = 1,
        vars = vars,
        optionalVars = optionalVars,
        meta = emptyMap(),
        body = body,
        source = PromptSource.BUILTIN,
    )

    @Test
    fun `substitutes variables and keeps if block when value present`() {
        val result = renderPrompt(template(), mapOf("a" to "世界", "b" to "小明"))
        val rendered = (result as Outcome.Ok).value
        assertThat(rendered.id).isEqualTo("test.tpl")
        assertThat(rendered.version).isEqualTo(1)
        assertThat(rendered.text).isEqualTo("你好 世界，小明！")
    }

    @Test
    fun `if block is dropped when value is empty`() {
        val rendered = (renderPrompt(template(), mapOf("a" to "世界", "b" to "")) as Outcome.Ok).value
        assertThat(rendered.text).isEqualTo("你好 世界！")
    }

    @Test
    fun `missing optional var renders as empty string`() {
        val rendered = (renderPrompt(template(), mapOf("a" to "世界")) as Outcome.Ok).value
        assertThat(rendered.text).isEqualTo("你好 世界！")
    }

    @Test
    fun `missing required var fails with prompt dot missing_var`() {
        val result = renderPrompt(template(), mapOf("b" to "x"))
        val error = (result as Outcome.Err).error
        assertThat(error.code).isEqualTo(PromptErrorCodes.MISSING_VAR)
        assertThat(error.message).contains("a")
    }

    @Test
    fun `template without vars renders body unchanged`() {
        val rendered =
            (
                renderPrompt(
                    template(vars = emptySet(), optionalVars = emptySet(), body = "固定文本"),
                    emptyMap(),
                ) as Outcome.Ok
            ).value
        assertThat(rendered.text).isEqualTo("固定文本")
    }

    @Test
    fun `multiple if blocks render independently`() {
        val tpl =
            template(
                vars = setOf("x", "y"),
                optionalVars = setOf("x", "y"),
                body = "{{#if x}}X{{/if}}-{{#if y}}Y{{/if}}",
            )
        assertThat((renderPrompt(tpl, mapOf("x" to "1", "y" to "")) as Outcome.Ok).value.text).isEqualTo("X-")
        assertThat((renderPrompt(tpl, mapOf("x" to "", "y" to "2")) as Outcome.Ok).value.text).isEqualTo("-Y")
    }
}
