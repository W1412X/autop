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

    @Test
    fun `region is reported as ignored for a11y and visual`() {
        assertThat(observationNotes(ObserveMode.A11Y, full = false, args = buildJsonObject { put("region", region) }))
            .containsExactly(REGION_IGNORED)
        assertThat(observationNotes(ObserveMode.VISUAL, full = false, args = buildJsonObject { put("region", region) }))
            .containsExactly(REGION_IGNORED)
    }

    @Test
    fun `region is not reported for llm`() {
        assertThat(observationNotes(ObserveMode.LLM, full = false, args = buildJsonObject { put("region", region) }))
            .isEmpty()
    }

    @Test
    fun `full is reported as ignored only for llm`() {
        assertThat(observationNotes(ObserveMode.LLM, full = true, args = buildJsonObject { }))
            .containsExactly(FULL_IGNORED)
        assertThat(observationNotes(ObserveMode.A11Y, full = true, args = buildJsonObject { }))
            .isEmpty()
    }

    @Test
    fun `both notes are reported when llm receives region and full`() {
        assertThat(
            observationNotes(ObserveMode.LLM, full = true, args = buildJsonObject { put("region", region) }),
        ).containsExactly(FULL_IGNORED)
    }
}
