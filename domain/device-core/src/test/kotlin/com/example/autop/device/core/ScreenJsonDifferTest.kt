/**
 * 观测差量（docs/design/observation-modes-coordinate-actions §3.4）：
 * 同页同模式才差量；换模式、换页、变化超阈值或 llm 一律完整。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeFlags
import com.example.autop.device.ObservationMode
import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowType
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
    fun `system window changes do not change fingerprint`() {
        val loose = TestScreens.config { hideSystemDecoration = false }
        val prev = TestScreens.serialize(screenWithStatusBar("12:00"), loose)
        val cur = TestScreens.serialize(screenWithStatusBar("12:01"), loose)
        // 状态栏时钟变了，但应用窗口没变：指纹必须相同，否则“屏幕未变化”永远判不出来。
        assertThat(cur.observation.fingerprint).isEqualTo(prev.observation.fingerprint)
    }

    @Test
    fun `page change returns full`() {
        val prev = TestScreens.serialize(screen("确定"))
        val other = screen("确定").copy(activity = "com.example.app.OtherActivity")
        assertThat(ScreenJsonDiffer.diff(prev, TestScreens.serialize(other), config).mode)
            .isEqualTo(ObservationMode.FULL)
    }

    /** 应用窗口 + 系统状态栏窗口（时钟按 [clock] 变化）。 */
    private fun screenWithStatusBar(clock: String): ScreenSnapshot =
        TestScreens.snapshot(
            windows =
                listOf(
                    TestScreens.window(windowId = 1) {
                        title = "设置"
                        rootIdx = 1
                    },
                    TestScreens.window(windowId = 2) {
                        type = WindowType.SYSTEM
                        packageName = "com.android.systemui"
                        title = "系统"
                        layer = 10
                        active = false
                        focused = false
                        rootIdx = 3
                    },
                ),
            nodes =
                listOf(
                    TestScreens.node(1) { children = listOf(2) },
                    TestScreens.node(2) {
                        parent = 1
                        className = "android.widget.Button"
                        text = "确定"
                        viewId = "confirm"
                        flags = NodeFlags.VISIBLE or NodeFlags.CLICKABLE or NodeFlags.ENABLED
                    },
                    TestScreens.node(3) { children = listOf(4) },
                    TestScreens.node(4) {
                        parent = 3
                        className = "android.widget.TextView"
                        text = clock
                        bounds = Rect(900, 0, 1080, 60)
                    },
                ),
        )

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
