package com.example.autop.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JsonTreeParserTest {
    @Test
    fun `plain text is not json`() {
        val tree = JsonTreeParser.parse("错误 tool_observe: 节点树为空")

        assertThat(tree.root).isNull()
        assertThat(tree.trailing).isEmpty()
    }

    @Test
    fun `object becomes branch with keyed children`() {
        val tree = JsonTreeParser.parse("""{"a":1,"b":"x"}""")

        val root = tree.root
        assertThat(root).isInstanceOf(JsonTreeNode.Branch::class.java)
        val branch = root as JsonTreeNode.Branch
        assertThat(branch.kind).isEqualTo(JsonTreeNode.Branch.Kind.OBJECT)
        assertThat(branch.children.map { it.key }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `array elements are keyed by index`() {
        val tree = JsonTreeParser.parse("""[10,true,null]""")

        val branch = tree.root as JsonTreeNode.Branch
        assertThat(branch.kind).isEqualTo(JsonTreeNode.Branch.Kind.ARRAY)
        assertThat(branch.children.map { it.key }).containsExactly("[0]", "[1]", "[2]").inOrder()
    }

    @Test
    fun `string value keeps json quotes`() {
        val tree = JsonTreeParser.parse("""{"text":"你好"}""")

        val leaf = (tree.root as JsonTreeNode.Branch).children.single() as JsonTreeNode.Leaf
        assertThat(leaf.value).isEqualTo("\"你好\"")
    }

    @Test
    fun `trailing text after json is kept aside`() {
        val tree = JsonTreeParser.parse("{\"a\":1}\n建议：使用无障碍模式")

        assertThat((tree.root as JsonTreeNode.Branch).children).hasSize(1)
        assertThat(tree.trailing).isEqualTo("建议：使用无障碍模式")
    }

    @Test
    fun `braces inside string do not end the value early`() {
        val tree = JsonTreeParser.parse("""{"text":"a}b{","n":2}""")

        val branch = tree.root as JsonTreeNode.Branch
        assertThat(branch.children.map { it.key }).containsExactly("text", "n").inOrder()
    }

    @Test
    fun `escaped quote inside string does not end the value early`() {
        val tree = JsonTreeParser.parse("""{"text":"say \"hi\"","n":2}""")

        val branch = tree.root as JsonTreeNode.Branch
        assertThat(branch.children.map { it.key }).containsExactly("text", "n").inOrder()
    }

    @Test
    fun `broken json falls back to plain text`() {
        val tree = JsonTreeParser.parse("""{"a":1""")

        assertThat(tree.root).isNull()
    }

    @Test
    fun `nested structure keeps hierarchy`() {
        val tree = JsonTreeParser.parse("""{"windows":[{"kids":[{"tag":"btn"}]}]}""")

        val root = tree.root as JsonTreeNode.Branch
        val windows = root.children.single() as JsonTreeNode.Branch
        assertThat(windows.key).isEqualTo("windows")
        assertThat(windows.kind).isEqualTo(JsonTreeNode.Branch.Kind.ARRAY)

        val window = windows.children.single() as JsonTreeNode.Branch
        assertThat(window.key).isEqualTo("[0]")
        assertThat(window.kind).isEqualTo(JsonTreeNode.Branch.Kind.OBJECT)

        val kids = window.children.single() as JsonTreeNode.Branch
        assertThat(kids.key).isEqualTo("kids")

        val kid = kids.children.single() as JsonTreeNode.Branch
        val tag = kid.children.single() as JsonTreeNode.Leaf
        assertThat(tag.key).isEqualTo("tag")
        assertThat(tag.value).isEqualTo("\"btn\"")
    }
}
