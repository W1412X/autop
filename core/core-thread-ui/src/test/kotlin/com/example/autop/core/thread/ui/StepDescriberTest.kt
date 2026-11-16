/**
 * 步骤描述（coordinate-node-action-split §5.9）：节点工具使用独立标签，
 * 参数取 id（scroll_node 多键取首个）；注册工具须全部有专属标签与图标。
 */
package com.example.autop.core.thread.ui

import com.example.autop.agent.ToolProbeFixtures
import com.example.autop.core.ui.component.AutopIcons
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StepDescriberTest {
    @Test
    fun `node tools map to their own labels`() {
        assertThat(stepLabelOf("tap_node")).isEqualTo(StepLabel.TAP_NODE)
        assertThat(stepLabelOf("scroll_node")).isEqualTo(StepLabel.SCROLL_NODE)
    }

    @Test
    fun `intent maps to its own label and shows the kind`() {
        assertThat(stepLabelOf("intent")).isEqualTo(StepLabel.INTENT)
        assertThat(stepArgOf("intent", """{"kind":"navigate"}""")).isEqualTo("navigate")
    }

    @Test
    fun `node tool args take id first`() {
        assertThat(stepArgOf("tap_node", """{"id":3}""")).isEqualTo("3")
        assertThat(stepArgOf("scroll_node", """{"id":3,"direction":"down"}""")).isEqualTo("3")
    }

    @Test
    fun `previously other tools map to dedicated labels`() {
        assertThat(stepLabelOf("run_shell")).isEqualTo(StepLabel.RUN_SHELL)
        assertThat(stepLabelOf("ask_user")).isEqualTo(StepLabel.ASK_USER)
        assertThat(stepLabelOf("memory_search")).isEqualTo(StepLabel.MEMORY_SEARCH)
        assertThat(stepLabelOf("patch_flow_node")).isEqualTo(StepLabel.PATCH_FLOW_NODE)
    }

    @Test
    fun `shell command is hidden from the collapsed row and shown in full`() {
        val args = """{"command":"pm list packages\n  | grep demo"}"""

        assertThat(stepArgOf("run_shell", args)).isNull()
        assertThat(stepShellCommandOf(args)).isEqualTo("pm list packages\n  | grep demo")
    }

    @Test
    fun `every registered tool has a dedicated label and icon`() {
        val tools = ToolProbeFixtures.names + "run_shell"
        for (tool in tools) {
            assertThat(stepLabelOf(tool)).isNotEqualTo(StepLabel.OTHER)
            assertThat(toolIconOf(tool)).isNotEqualTo(AutopIcons.HelpOutline)
        }
    }
}
