/**
 * 执行回执（agent-harness-overhaul WP2.11）：工具必须如实说明执行方式、注入通道、
 * 回退原因与 shell 跳过原因；文案全部来自 prompt 模板。
 */
package com.example.autop.device.impl.tools

import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.device.InjectionChannel
import com.example.autop.device.impl.nodeaction.NodeRoute
import com.example.autop.device.impl.nodeaction.SkipReason
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
    fun `gesture line reports shell channel and why node action was skipped`() {
        val line =
            trace.executionLine(
                ExecMethod.GESTURE,
                channel = InjectionChannel.SHELL,
                fallback = trace.routeFallback(NodeRoute.Skipped(SkipReason.NO_NODE), "ACTION_CLICK"),
            )
        assertThat(line).isEqualTo("执行：坐标手势（shell 注入）；未走节点动作：落点处没有可执行该动作的节点")
    }

    @Test
    fun `gesture line reports accessibility channel and rejection fallback`() {
        val line =
            trace.executionLine(
                ExecMethod.GESTURE,
                channel = InjectionChannel.ACCESSIBILITY,
                fallback = trace.routeFallback(NodeRoute.Rejected("com.example.app"), "ACTION_LONG_CLICK"),
            )
        assertThat(line).isEqualTo("执行：坐标手势（无障碍注入）；节点动作被应用拒绝（ACTION_LONG_CLICK），本次改用坐标手势")
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
            .isEqualTo("执行：全局动作（shell 注入）")
        assertThat(trace.executionLine(ExecMethod.SHELL_KEY, actionLabel = "剪贴板粘贴"))
            .isEqualTo("执行：shell 键盘注入（剪贴板粘贴）")
    }

    @Test
    fun `unknown channel is stated instead of guessed`() {
        assertThat(trace.executionLine(ExecMethod.GESTURE, channel = InjectionChannel.NONE))
            .isEqualTo("执行：坐标手势（注入通道未知）")
    }

    @Test
    fun `done route has no fallback explanation`() {
        assertThat(trace.routeFallback(NodeRoute.Done("com.example.app"), "ACTION_CLICK")).isNull()
    }

    @Test
    fun `every skip reason is explained to the model`() {
        val reasons = SkipReason.entries.associateWith { it.describe() }
        assertThat(reasons).hasSize(SkipReason.entries.size)
        for ((reason, text) in reasons) {
            assertThat(text).isNotEmpty()
            assertThat(trace.routeFallback(NodeRoute.Skipped(reason), "ACTION_SCROLL_DOWN"))
                .isEqualTo("未走节点动作：$text")
        }
        assertThat(reasons[SkipReason.AREA]).contains("面积过大")
        assertThat(reasons[SkipReason.BLOCKED]).contains("改用手势")
    }
}
