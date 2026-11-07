/**
 * [fallbackCallsOf] 单测：回退协议正文恢复工具调用，id 与执行侧 `fb_<turn>_<i>` 一致；
 * 原生 ToolCall 块或普通文本不产生回退调用。
 */
package com.example.autop.flow.impl.author

import com.example.autop.llm.Block
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FallbackCallsTest {
    @Test
    fun `协议 JSON 正文恢复调用与参数`() {
        val text = """{"tool_calls":[{"name":"tap","arguments":{"x":1}},{"name":"swipe","arguments":{"dx":100}}]}"""

        val calls = fallbackCallsOf(listOf(Block.Text(text)), turn = 3)

        assertThat(calls.map { it.id }).containsExactly("fb_3_0", "fb_3_1").inOrder()
        assertThat(calls.map { it.name }).containsExactly("tap", "swipe").inOrder()
        assertThat(calls[0].argsJson).isEqualTo("""{"x":1}""")
    }

    @Test
    fun `原生 ToolCall 块或普通文本不产生回退调用`() {
        assertThat(fallbackCallsOf(listOf(Block.ToolCall("c1", "tap", "{}")), turn = 1)).isEmpty()
        assertThat(fallbackCallsOf(listOf(Block.Text("好的，我来帮你")), turn = 1)).isEmpty()
        assertThat(fallbackCallsOf(emptyList(), turn = 1)).isEmpty()
    }
}
