/**
 * 观测参数被忽略时的显式说明（docs/design/observation-modes-coordinate-actions §5.9）：
 * 静默忽略会让模型误以为参数生效。
 */
package com.example.autop.device.impl.tools

import com.example.autop.device.ObserveMode
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ObserveNotesTest {
    private val region = buildJsonObject { put("x1", 0) }
    private val withRegion = buildJsonObject { put("region", region) }
    private val noRegion = buildJsonObject { }

    private val render: (String) -> String = { id -> "rendered:$id" }

    @Test
    fun `region is reported as ignored for a11y and visual`() {
        val a11y = observationNotes(ObserveMode.A11Y, full = false, args = withRegion, render = render)
        val visual = observationNotes(ObserveMode.VISUAL, full = false, args = withRegion, render = render)
        assertThat(a11y).containsExactly("rendered:tool.observe.region_ignored")
        assertThat(visual).containsExactly("rendered:tool.observe.region_ignored")
    }

    @Test
    fun `region is not reported for llm`() {
        assertThat(observationNotes(ObserveMode.LLM, full = false, args = withRegion, render = render)).isEmpty()
    }

    @Test
    fun `full is reported as ignored only for llm`() {
        val llm = observationNotes(ObserveMode.LLM, full = true, args = noRegion, render = render)
        val a11y = observationNotes(ObserveMode.A11Y, full = true, args = noRegion, render = render)
        assertThat(llm).containsExactly("rendered:tool.observe.full_ignored")
        assertThat(a11y).isEmpty()
    }

    @Test
    fun `both notes are reported when llm receives region and full`() {
        val notes = observationNotes(ObserveMode.LLM, full = true, args = withRegion, render = render)
        assertThat(notes).containsExactly("rendered:tool.observe.full_ignored")
    }
}
