/**
 * [displayTextOf] 单测：回退协议 JSON 不冒充助手正文、工具调用标记加识别提示、其余原样。
 */
package com.example.autop.agent.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MsgTextTest {
    @Test
    fun `普通文本原样返回`() {
        assertThat(displayTextOf("好的，我来帮你打开设置。")).isEqualTo("好的，我来帮你打开设置。")
    }

    @Test
    fun `回退协议 tool_calls 整体不上屏`() {
        assertThat(displayTextOf("""{"tool_calls":[{"name":"tap","arguments":{}}]}""")).isEmpty()
    }

    @Test
    fun `回退协议 final 只展示正文`() {
        assertThat(displayTextOf("""{"final":"已完成"}""")).isEqualTo("已完成")
    }

    @Test
    fun `带代码围栏的协议 JSON 同样识别`() {
        assertThat(displayTextOf("```json\n{\"tool_calls\":[{\"name\":\"tap\",\"arguments\":{}}]}\n```")).isEmpty()
    }

    @Test
    fun `无关或非法 JSON 原样返回`() {
        assertThat(displayTextOf("""{"foo":1}""")).isEqualTo("""{"foo":1}""")
        assertThat(displayTextOf("{ 不完整")).isEqualTo("{ 不完整")
    }

    @Test
    fun `正文前有说明文字时不吞正文`() {
        val text = """先看一下 {"final":"你好"} 再说"""
        assertThat(displayTextOf(text)).isEqualTo(text)
    }

    @Test
    fun `工具调用 XML 标记加识别提示且保留原文`() {
        val text = "好的\n<tool_call><function=tap></function></tool_call>"
        assertThat(displayTextOf(text)).isEqualTo("$TOOL_MARK_NOTE\n\n$text")
    }

    @Test
    fun `function 标记同样加识别提示`() {
        val text = "<function=tap></function>"
        assertThat(displayTextOf(text)).isEqualTo("$TOOL_MARK_NOTE\n\n$text")
    }
}
