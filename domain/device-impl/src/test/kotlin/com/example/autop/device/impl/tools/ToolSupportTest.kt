/**
 * 注入失败回执：两条通道都没送达时，不能说成“手势被取消”（会让模型误判为系统主动取消）。
 */
package com.example.autop.device.impl.tools

import com.example.autop.device.DeviceErrorCodes
import com.example.autop.llm.Block
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolSupportTest {
    @Test
    fun `inject failure names both channels and is not reported as cancelled`() {
        val error = injectFailed("点击")

        assertThat(error.code).isEqualTo(DeviceErrorCodes.ACTION_FAILED)
        val message = error.message
        assertThat(message).contains("shell")
        assertThat(message).contains("无障碍")
        assertThat(message).contains("未送达")
        assertThat(message).doesNotContain("取消")
        assertThat(error.hint).isNotNull()
    }

    @Test
    fun `inject failure output exposes the hint to the model`() {
        val out = errorOutput(injectFailed("滑动"))

        assertThat(out.ok).isFalse()
        val visible = (out.content.first() as Block.Text).text
        assertThat(visible).contains("建议：")
    }
}
