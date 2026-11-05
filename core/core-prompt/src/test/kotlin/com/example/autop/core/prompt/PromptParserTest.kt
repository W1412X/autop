package com.example.autop.core.prompt

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PromptParserTest {
    @Test
    fun `parses header lists meta and body`() {
        val template =
            parsePromptTemplate(
                """
                ---
                id: tool.tap
                version: 2
                vars: [id, x]
                optionalVars: [x]
                meta:
                  param.id: 要点击的节点编号
                  param.x: 横坐标
                ---
                点击 {{id}}{{#if x}} 或位置 {{x}}{{/if}}。
                """.trimIndent(),
                PromptSource.BUILTIN,
            )
        assertThat(template.id).isEqualTo("tool.tap")
        assertThat(template.version).isEqualTo(2)
        assertThat(template.vars).containsExactly("id", "x")
        assertThat(template.optionalVars).containsExactly("x")
        assertThat(template.meta).containsExactly("param.id", "要点击的节点编号", "param.x", "横坐标")
        assertThat(template.body).isEqualTo("点击 {{id}}{{#if x}} 或位置 {{x}}{{/if}}。")
        assertThat(template.source).isEqualTo(PromptSource.BUILTIN)
    }

    @Test
    fun `missing vars key means no variables`() {
        val template = parsePromptTemplate("---\nid: a.b\nversion: 1\n---\n正文", PromptSource.BUILTIN)
        assertThat(template.vars).isEmpty()
        assertThat(template.optionalVars).isEmpty()
        assertThat(template.meta).isEmpty()
    }

    @Test
    fun `missing id or version is a failure`() {
        assertThrows(IllegalStateException::class.java) {
            parsePromptTemplate("---\nversion: 1\n---\n正文", PromptSource.BUILTIN)
        }
        assertThrows(IllegalStateException::class.java) {
            parsePromptTemplate("---\nid: a.b\n---\n正文", PromptSource.BUILTIN)
        }
    }

    @Test
    fun `unknown or duplicate header key is a failure`() {
        assertThrows(IllegalStateException::class.java) {
            parsePromptTemplate("---\nid: a.b\nversion: 1\nnope: 1\n---\n正文", PromptSource.BUILTIN)
        }
        assertThrows(IllegalStateException::class.java) {
            parsePromptTemplate("---\nid: a.b\nid: a.c\nversion: 1\n---\n正文", PromptSource.BUILTIN)
        }
    }

    @Test
    fun `optionalVars must be a subset of vars`() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                parsePromptTemplate(
                    "---\nid: a.b\nversion: 1\nvars: [x]\noptionalVars: [y]\n---\n{{x}}",
                    PromptSource.BUILTIN,
                )
            }
        assertThat(error).hasMessageThat().contains("optionalVars")
    }

    @Test
    fun `undeclared variable fails with prompt dot undeclared_var`() {
        val error =
            assertThrows(PromptLoadException::class.java) {
                parsePromptTemplate("---\nid: a.b\nversion: 1\nvars: [x]\n---\n{{x}} {{y}}", PromptSource.BUILTIN)
            }
        assertThat(error.error.code).isEqualTo(PromptErrorCodes.UNDECLARED_VAR)
        assertThat(error.error.message).contains("y")
    }

    @Test
    fun `if variable must also be declared`() {
        val error =
            assertThrows(PromptLoadException::class.java) {
                parsePromptTemplate("---\nid: a.b\nversion: 1\n---\n{{#if y}}x{{/if}}", PromptSource.BUILTIN)
            }
        assertThat(error.error.code).isEqualTo(PromptErrorCodes.UNDECLARED_VAR)
    }

    @Test
    fun `unmatched nested or invalid if tokens fail`() {
        assertThrows(IllegalStateException::class.java) {
            parsePromptTemplate("---\nid: a.b\nversion: 1\n---\n{{/if}}", PromptSource.BUILTIN)
        }
        assertThrows(IllegalStateException::class.java) {
            parsePromptTemplate(
                "---\nid: a.b\nversion: 1\nvars: [x]\n---\n{{#if x}}{{#if x}}a{{/if}}{{/if}}",
                PromptSource.BUILTIN,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            parsePromptTemplate("---\nid: a.b\nversion: 1\nvars: [x]\n---\n{{#if x}}a", PromptSource.BUILTIN)
        }
        assertThrows(IllegalStateException::class.java) {
            parsePromptTemplate("---\nid: a.b\nversion: 1\n---\n{{bad name}}", PromptSource.BUILTIN)
        }
    }

    @Test
    fun `header must be delimited and CRLF is accepted`() {
        val template =
            parsePromptTemplate("---\r\nid: a.b\r\nversion: 1\r\n---\r\n正文\r\n", PromptSource.USER)
        assertThat(template.body).isEqualTo("正文")
        assertThat(template.source).isEqualTo(PromptSource.USER)
        assertThrows(IllegalStateException::class.java) {
            parsePromptTemplate("id: a.b", PromptSource.BUILTIN)
        }
        assertThrows(IllegalStateException::class.java) {
            parsePromptTemplate("---\nid: a.b\nversion: 1\n正文", PromptSource.BUILTIN)
        }
    }
}
