package com.example.autop.core.prompt.impl

import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptErrorCodes
import com.example.autop.core.prompt.PromptSource
import com.example.autop.core.prompt.PromptTemplate
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class PromptOverrideEditorImplTest {
    private val builtin =
        PromptTemplate(
            id = "x.tpl",
            version = 1,
            vars = setOf("v"),
            optionalVars = emptySet(),
            meta = emptyMap(),
            body = "你好 {{v}}",
            source = PromptSource.BUILTIN,
        )
    private val store = FakePromptOverridesStore()
    private val editor = PromptOverrideEditorImpl(mapOf(builtin.id to builtin), store)

    @Test
    fun `valid override body is accepted and stored`() =
        runTest {
            val result = editor.setOverride("x.tpl", "改写 {{v}}")
            assertThat(result).isInstanceOf(Outcome.Ok::class.java)
            assertThat(store.current()).containsExactly("x.tpl", "改写 {{v}}")
        }

    @Test
    fun `undeclared variable is rejected and not stored`() =
        runTest {
            val result = editor.setOverride("x.tpl", "改写 {{ghost}}")
            val error = (result as Outcome.Err).error
            assertThat(error.code).isEqualTo(PromptErrorCodes.UNDECLARED_VAR)
            assertThat(store.current()).isEmpty()
        }

    @Test
    fun `valid if structure is accepted`() =
        runTest {
            val result = editor.setOverride("x.tpl", "{{#if v}}有 {{v}}{{/if}}")
            assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        }

    @Test
    fun `unmatched if is a programming error`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { editor.setOverride("x.tpl", "{{#if v}}没有结束") }
        }
    }

    @Test
    fun `unknown id is a programming error`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { editor.setOverride("ghost", "x") }
        }
    }

    @Test
    fun `clearOverride removes the stored body`() =
        runTest {
            editor.setOverride("x.tpl", "改写 {{v}}")
            editor.clearOverride("x.tpl")
            assertThat(store.current()).isEmpty()
        }
}
