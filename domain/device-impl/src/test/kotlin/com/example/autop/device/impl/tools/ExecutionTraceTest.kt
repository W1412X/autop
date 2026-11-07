/**
 * 执行回执（docs/design/observation-modes-coordinate-actions §5.9）：
 * 工具必须如实说明执行方式、注入通道、回退原因和效果三态。
 */
package com.example.autop.device.impl.tools

import com.example.autop.device.InjectionChannel
import com.example.autop.device.impl.nodeaction.NodeRoute
import com.example.autop.device.impl.nodeaction.SkipReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExecutionTraceTest {
    @Test
    fun `node action line names the accessibility action`() {
        assertThat(executionLine(ExecMethod.NODE_ACTION, actionLabel = "ACTION_CLICK"))
            .isEqualTo("执行：无障碍节点动作 ACTION_CLICK")
    }

    @Test
    fun `gesture line reports shell channel and why node action was skipped`() {
        val line =
            executionLine(
                ExecMethod.GESTURE,
                channel = InjectionChannel.SHELL,
                fallback = routeFallback(NodeRoute.Skipped(SkipReason.NO_NODE), "ACTION_CLICK"),
            )
        assertThat(line).isEqualTo("执行：坐标手势（shell 注入）；未走节点动作：落点处没有可执行该动作的节点")
    }

    @Test
    fun `gesture line reports accessibility channel and rejection fallback`() {
        val line =
            executionLine(
                ExecMethod.GESTURE,
                channel = InjectionChannel.ACCESSIBILITY,
                fallback = routeFallback(NodeRoute.Rejected("com.example.app"), "ACTION_LONG_CLICK"),
            )
        assertThat(line).isEqualTo("执行：坐标手势（无障碍注入）；节点动作被应用拒绝（ACTION_LONG_CLICK），本次改用坐标手势")
    }

    @Test
    fun `global action and shell key carry their own labels`() {
        assertThat(executionLine(ExecMethod.GLOBAL_ACTION, channel = InjectionChannel.SHELL))
            .isEqualTo("执行：全局动作（shell 注入）")
        assertThat(executionLine(ExecMethod.SHELL_KEY, actionLabel = "剪贴板粘贴"))
            .isEqualTo("执行：shell 键盘注入（剪贴板粘贴）")
    }

    @Test
    fun `unknown channel is stated instead of guessed`() {
        assertThat(executionLine(ExecMethod.GESTURE, channel = InjectionChannel.NONE))
            .isEqualTo("执行：坐标手势（注入通道未知）")
    }

    @Test
    fun `done route has no fallback explanation`() {
        assertThat(routeFallback(NodeRoute.Done("com.example.app"), "ACTION_CLICK")).isNull()
    }

    @Test
    fun `every skip reason is explained to the model`() {
        val reasons = SkipReason.entries.associateWith { it.describe() }
        assertThat(reasons).hasSize(SkipReason.entries.size)
        for ((reason, text) in reasons) {
            assertThat(text).isNotEmpty()
            assertThat(routeFallback(NodeRoute.Skipped(reason), "ACTION_SCROLL_DOWN"))
                .isEqualTo("未走节点动作：$text")
        }
        assertThat(reasons[SkipReason.AREA]).contains("面积过大")
        assertThat(reasons[SkipReason.BLOCKED]).contains("改用手势")
    }

    @Test
    fun `effect line has three distinct states`() {
        assertThat(effectLine(ExecEffect.CHANGED)).isEqualTo("效果：界面已变化")
        assertThat(effectLine(ExecEffect.UNCHANGED, "到列表底了"))
            .isEqualTo("效果：屏幕没有变化（device.no_effect）：到列表底了")
        assertThat(effectLine(ExecEffect.UNKNOWN, "需要时用 observe 确认")).isEqualTo("效果：无法确认：需要时用 observe 确认")
        assertThat(effectLine(ExecEffect.UNCHANGED, " ")).isEqualTo("效果：屏幕没有变化（device.no_effect）")
    }

    @Test
    fun `fingerprint result maps to effect state`() {
        assertThat(effectOf(true)).isEqualTo(ExecEffect.CHANGED)
        assertThat(effectOf(false)).isEqualTo(ExecEffect.UNCHANGED)
        assertThat(effectOf(null)).isEqualTo(ExecEffect.UNKNOWN)
    }
}
