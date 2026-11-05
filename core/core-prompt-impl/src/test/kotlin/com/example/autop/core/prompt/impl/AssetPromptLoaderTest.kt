package com.example.autop.core.prompt.impl

import com.example.autop.core.prompt.PromptErrorCodes
import com.example.autop.core.prompt.PromptLoadException
import com.example.autop.core.prompt.PromptSource
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class AssetPromptLoaderTest {
    @Test
    fun `loads templates into a map keyed by template id`() {
        val files =
            listOf(
                PromptAssetFile("a.md", "---\nid: x.a\nversion: 1\nvars: [v]\n---\n你好 {{v}}"),
                PromptAssetFile("b.md", "---\nid: x.b\nversion: 2\nvars: []\n---\n固定"),
            )
        val templates = loadBuiltinTemplates(files)
        assertThat(templates.keys).containsExactly("x.a", "x.b")
        assertThat(templates.getValue("x.a").source).isEqualTo(PromptSource.BUILTIN)
        assertThat(templates.getValue("x.b").version).isEqualTo(2)
    }

    @Test
    fun `duplicate template id is a startup failure`() {
        val files =
            listOf(
                PromptAssetFile("a.md", "---\nid: x.dup\nversion: 1\nvars: []\n---\n甲"),
                PromptAssetFile("b.md", "---\nid: x.dup\nversion: 1\nvars: []\n---\n乙"),
            )
        val e = assertThrows(IllegalStateException::class.java) { loadBuiltinTemplates(files) }
        assertThat(e).hasMessageThat().contains("duplicate prompt id")
        assertThat(e).hasMessageThat().contains("x.dup")
    }

    @Test
    fun `undeclared variable in body is a load failure with prompt error code`() {
        val files = listOf(PromptAssetFile("a.md", "---\nid: x.a\nversion: 1\nvars: []\n---\n你好 {{ghost}}"))
        val e = assertThrows(PromptLoadException::class.java) { loadBuiltinTemplates(files) }
        assertThat(e.error.code).isEqualTo(PromptErrorCodes.UNDECLARED_VAR)
    }

    @Test
    fun `broken header is a startup failure`() {
        val files = listOf(PromptAssetFile("a.md", "id: x.a\nversion: 1"))
        val e = assertThrows(IllegalStateException::class.java) { loadBuiltinTemplates(files) }
        assertThat(e).hasMessageThat().contains("---")
    }
}
