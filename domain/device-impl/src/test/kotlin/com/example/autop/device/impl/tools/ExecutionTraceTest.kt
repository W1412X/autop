/**
 * 执行回执（agent-harness-overhaul WP2.11）：工具必须如实说明执行方式、注入通道与
 * shell 跳过原因；文案全部来自 prompt 模板。路由回退说明随节点路由移除（ADR-0019）。
 */
package com.example.autop.device.impl.tools

import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.device.InjectionChannel
import com.example.autop.device.ShellSkip
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExecutionTraceTest {
    private val trace = ExecutionTraceRenderer(FakePromptRepository())

    @Test
    fun `node action line names the accessibility action`() {
        assertThat(trace.executionLine(ExecMethod.NODE_ACTION, actionLabel = "ACTION_CLICK"))
            .isEqualTo("执行：无障碍节点动作 ACTION_CLICK")
    }

    @Test
    fun `gesture line reports shell channel`() {
        assertThat(trace.executionLine(ExecMethod.GESTURE, channel = InjectionChannel.SHELL))
            .isEqualTo("执行：坐标手势（shell 注入（命令已发出））")
    }

    @Test
    fun `gesture via accessibility with shell not connected mentions reason`() {
        val line =
            trace.executionLine(
                ExecMethod.GESTURE,
                channel = InjectionChannel.ACCESSIBILITY,
                shellSkip = ShellSkip.NOT_CONNECTED,
            )
        assertThat(line).isEqualTo("执行：坐标手势（无障碍注入；shell 通道未连接）")
    }

    @Test
    fun `global action and shell key carry their own labels`() {
        assertThat(trace.executionLine(ExecMethod.GLOBAL_ACTION, channel = InjectionChannel.SHELL))
            .isEqualTo("执行：全局动作（shell 注入（命令已发出））")
        assertThat(trace.executionLine(ExecMethod.SHELL_KEY, actionLabel = "剪贴板粘贴"))
            .isEqualTo("执行：shell 键盘注入（剪贴板粘贴）")
    }

    @Test
    fun `unknown channel is stated instead of guessed`() {
        assertThat(trace.executionLine(ExecMethod.GESTURE, channel = InjectionChannel.NONE))
            .isEqualTo("执行：坐标手势（注入通道未知）")
    }
}
