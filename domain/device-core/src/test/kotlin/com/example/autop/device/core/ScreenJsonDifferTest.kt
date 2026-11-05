/**
 * 观测差量（docs/design/observation-modes-coordinate-actions §3.4）：
 * 同页同模式才差量；换模式、换页、变化超阈值或 llm 一律完整。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeFlags
import com.example.autop.device.ObservationMode
import com.example.autop.device.ObserveMode
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ScreenJsonDifferTest {
    private val config = TestScreens.config()

    @Test
    fun `no baseline returns full observation`() {
        val cur = TestScreens.serialize(screen("确定"))
        val observation = ScreenJsonDiffer.diff(null, cur, config)
        assertThat(observation.mode).isEqualTo(ObservationMode.FULL)
    }

    @Test
    fun `same page changed state yields diff`() {
        val prev = TestScreens.serialize(screen("确定"))
        val cur = TestScreens.serialize(screen("确定", enabled = false))
        val observation = ScreenJsonDiffer.diff(prev, cur, config.copy(diffMaxRatio = 1.0))
        assertThat(observation.mode).isEqualTo(ObservationMode.DIFF)
        val diff =
            Json
                .parseToJsonElement(observation.text)
                .jsonObject
                .getValue("diff")
                .jsonObject
        assertThat(
            diff.getValue("changed").jsonArray.single().jsonObject.getValue("st").jsonArray.map {
                it.jsonPrimitive.content
            },
        ).containsExactly("disabled")
    }

    @Test
    fun `mode switch always returns full`() {
        val prev = TestScreens.serialize(screen("确定"), mode = ObserveMode.A11Y)
        val cur = TestScreens.serialize(screen("确定"), mode = ObserveMode.VISUAL)
        assertThat(ScreenJsonDiffer.diff(prev, cur, config).mode).isEqualTo(ObservationMode.FULL)
    }

    @Test
    fun `llm always returns full`() {
        val prev = TestScreens.serialize(screen("确定"), mode = ObserveMode.LLM)
        val cur = TestScreens.serialize(screen("确定"), mode = ObserveMode.LLM)
        assertThat(ScreenJsonDiffer.diff(prev, cur, config).mode).isEqualTo(ObservationMode.FULL)
    }

    @Test
    fun `change ratio above threshold returns full`() {
        val prev = TestScreens.serialize(screen("确定"))
        val cur = TestScreens.serialize(screen("取消"))
        val observation = ScreenJsonDiffer.diff(prev, cur, config.copy(diffMaxRatio = 0.1))
        assertThat(observation.mode).isEqualTo(ObservationMode.FULL)
    }

    @Test
    fun `page change returns full`() {
        val prev = TestScreens.serialize(screen("确定"))
        val other = screen("确定").copy(activity = "com.example.app.OtherActivity")
        assertThat(ScreenJsonDiffer.diff(prev, TestScreens.serialize(other), config).mode)
            .isEqualTo(ObservationMode.FULL)
    }

    private fun screen(
        label: String,
        enabled: Boolean = true,
    ) = TestScreens.snapshot(
        windows = listOf(TestScreens.window { title = "设置" }),
        nodes =
            listOf(
                TestScreens.node(1) { children = listOf(2) },
                TestScreens.node(2) {
                    parent = 1
                    className = "android.widget.Button"
                    text = label
                    viewId = "confirm"
                    flags =
                        NodeFlags.VISIBLE or NodeFlags.CLICKABLE or
                        (if (enabled) NodeFlags.ENABLED else 0)
                },
            ),
    )
}
