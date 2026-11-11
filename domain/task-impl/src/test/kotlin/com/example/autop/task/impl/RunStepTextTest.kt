/**
 * 当前操作描述（docs/tech/07 §4.3 currentStep 展示）：动词映射、目标截断、坏参数防御。
 */
package com.example.autop.task.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RunStepTextTest {
    @Test
    fun `tap 是点击，long 为真变长按`() {
        assertThat(stepTextOf("tool.tap", """{"x":1,"y":2}""")).isEqualTo("点击")
        assertThat(stepTextOf("tool.tap", """{"long":true}""")).isEqualTo("长按")
        assertThat(stepTextOf("tool.tap", """{"long":"yes"}""")).isEqualTo("长按")
        assertThat(stepTextOf("tool.tap_node", """{"id":3}""")).isEqualTo("点击")
        assertThat(stepTextOf("tool.scroll_node", """{"id":3,"direction":"down"}""")).isEqualTo("滚动")
    }

    @Test
    fun `input_text 附带输入文本`() {
        assertThat(stepTextOf("tool.input_text", """{"text":"你好"}""")).isEqualTo("输入「你好」")
    }

    @Test
    fun `目标过长截断加省略号`() {
        val text = stepTextOf("tool.input_text", """{"text":"一二三四五六七八九十一二三四五"}""")
        assertThat(text).isEqualTo("输入「一二三四五六七八九十一二…」")
    }

    @Test
    fun `open_app 用应用名回落包名`() {
        assertThat(stepTextOf("tool.open_app", """{"name":"微信"}""")).isEqualTo("打开应用「微信」")
        assertThat(stepTextOf("tool.open_app", """{"package":"com.tencent.mm"}""")).isEqualTo("打开应用「com.tencent.…」")
    }

    @Test
    fun `global_action 映射动作说法`() {
        assertThat(stepTextOf("tool.global_action", """{"action":"back"}""")).isEqualTo("返回")
        assertThat(stepTextOf("tool.global_action", """{"action":"unknown"}""")).isEqualTo("执行系统动作")
    }

    @Test
    fun `无目标工具只给动词`() {
        assertThat(stepTextOf("tool.screenshot", """{"reason":"看下"}""")).isEqualTo("截屏")
        assertThat(stepTextOf("tool.wait", "")).isEqualTo("等待")
    }

    @Test
    fun `未知工具回落去前缀 id，坏参数与显式 null 不影响`() {
        assertThat(stepTextOf("tool.mystery", "not json")).isEqualTo("mystery")
        assertThat(stepTextOf("tool.input_text", """{"text":null}""")).isEqualTo("输入")
        assertThat(stepTextOf("tool.input_text", """{"text":""}""")).isEqualTo("输入")
    }

    @Test
    fun `targetOf 取展示就绪目标`() {
        assertThat(targetOf("tool.input_text", """{"text":"你好"}""")).isEqualTo("你好")
        assertThat(targetOf("tool.open_app", """{"name":"微信"}""")).isEqualTo("微信")
        assertThat(targetOf("tool.global_action", """{"action":"back"}""")).isEqualTo("返回")
        assertThat(targetOf("tool.tap", """{"x":1,"y":2}""")).isNull()
        assertThat(targetOf("tool.input_text", """{"text":null}""")).isNull()
    }

    @Test
    fun `targetOf 过长截断加省略号`() {
        assertThat(targetOf("tool.input_text", """{"text":"一二三四五六七八九十一二三四五"}"""))
            .isEqualTo("一二三四五六七八九十一二…")
    }

    @Test
    fun `模型可见裸名与工具 id 同等命中`() {
        // ToolCall.name 是模型可见裸名（无 tool. 前缀），两形态都要查中表
        assertThat(stepTextOf("tap", """{"x":1,"y":2}""")).isEqualTo("点击")
        assertThat(stepTextOf("global_action", """{"action":"back"}""")).isEqualTo("返回")
        assertThat(stepTextOf("input_text", """{"text":"你好"}""")).isEqualTo("输入「你好」")
        assertThat(targetOf("open_app", """{"name":"微信"}""")).isEqualTo("微信")
    }
}
