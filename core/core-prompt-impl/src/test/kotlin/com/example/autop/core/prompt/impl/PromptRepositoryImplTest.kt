package com.example.autop.core.prompt.impl

import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptSource
import com.example.autop.core.prompt.PromptTemplate
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PromptRepositoryImplTest {
    private val builtin =
        PromptTemplate(
            id = "x.tpl",
            version = 1,
            vars = setOf("v"),
            optionalVars = emptySet(),
            meta = mapOf("note" to "n"),
            body = "你好 {{v}}",
            source = PromptSource.BUILTIN,
        )
    private val other =
        PromptTemplate(
            id = "a.tpl",
            version = 3,
            vars = emptySet(),
            optionalVars = emptySet(),
            meta = emptyMap(),
            body = "固定",
            source = PromptSource.BUILTIN,
        )

    private fun repository(overrides: Map<String, String> = emptyMap()) =
        PromptRepositoryImpl(mapOf(builtin.id to builtin, other.id to other), FakePromptOverridesStore(overrides))

    @Test
    fun `returns builtin template when no override exists`() {
        val template = repository().get("x.tpl")
        assertThat(template.source).isEqualTo(PromptSource.BUILTIN)
        assertThat(template.body).isEqualTo("你好 {{v}}")
    }

    @Test
    fun `override replaces body and keeps builtin header`() {
        val template = repository(mapOf("x.tpl" to "改写 {{v}}")).get("x.tpl")
        assertThat(template.source).isEqualTo(PromptSource.USER)
        assertThat(template.body).isEqualTo("改写 {{v}}")
        assertThat(template.id).isEqualTo("x.tpl")
        assertThat(template.version).isEqualTo(1)
        assertThat(template.vars).containsExactly("v")
        assertThat(template.meta).containsExactly("note", "n")
    }

    @Test
    fun `render uses the overridden body`() {
        val rendered = repository(mapOf("x.tpl" to "改写 {{v}}")).render("x.tpl", mapOf("v" to "世界"))
        val text = (rendered as Outcome.Ok).value.text
        assertThat(text).isEqualTo("改写 世界")
    }

    @Test
    fun `all returns every template sorted by id`() {
        val all = repository(mapOf("x.tpl" to "改写 {{v}}")).all()
        assertThat(all.map { it.id }).containsExactly("a.tpl", "x.tpl").inOrder()
        assertThat(all.last().source).isEqualTo(PromptSource.USER)
    }

    @Test
    fun `unknown id is a programming error`() {
        val e = assertThrows(IllegalStateException::class.java) { repository().get("ghost") }
        assertThat(e).hasMessageThat().contains("ghost")
    }
}
