/**
 * 步骤描述（coordinate-node-action-split §5.9）：节点工具映射到点击/滚动标签，
 * 参数取 id（scroll_node 多键取首个）。
 */
package com.example.autop.core.thread.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StepDescriberTest {
    @Test
    fun `node tools map to tap and scroll labels`() {
        assertThat(stepLabelOf("tap_node")).isEqualTo(StepLabel.TAP)
        assertThat(stepLabelOf("scroll_node")).isEqualTo(StepLabel.SCROLL)
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
}
