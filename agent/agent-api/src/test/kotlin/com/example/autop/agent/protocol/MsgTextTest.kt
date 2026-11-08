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

    // ---- finalSummaryOf：收口总结净化（N170 审计） ----

    /** 拼接构造工具调用标记，避免源码出现连续标记字面量。 */
    private val toolMarkup =
        "<" + "tool_call><" + "function=screenshot><" + "parameter=reason>核实</" + "parameter></" +
            "function></" + "tool_call>"

    @Test
    fun `净化保留纯文本正文`() {
        assertThat(finalSummaryOf("卡在搜索页，未能发送消息。")).isEqualTo("卡在搜索页，未能发送消息。")
    }

    @Test
    fun `净化整体工具调用块返回 null`() {
        assertThat(finalSummaryOf(toolMarkup)).isNull()
    }

    @Test
    fun `净化正文混工具块只留正文`() {
        assertThat(finalSummaryOf("卡在搜索页。\n$toolMarkup")).isEqualTo("卡在搜索页。")
    }

    @Test
    fun `净化回退协议 tool_calls 返回 null`() {
        assertThat(finalSummaryOf("""{"tool_calls":[{"name":"tap","arguments":{}}]}""")).isNull()
    }

    @Test
    fun `净化回退协议 final 取正文`() {
        assertThat(finalSummaryOf("""{"final":"已完成"}""")).isEqualTo("已完成")
    }

    @Test
    fun `净化空白返回 null`() {
        assertThat(finalSummaryOf("   \n  ")).isNull()
    }
}
