/**
 * 统一可信口径（agent-harness-overhaul WP1.3）：信息不足对所有方式同口径，
 * suggest 为生效方式中的下一种；不再有 uncertain 字段。
 */
package com.example.autop.device.core

import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenNode
import com.example.autop.device.ScreenQuality
import com.example.autop.device.ScreenWindow
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class ScreenModelBuilderTest {
    @Test
    fun `visual degenerate is insufficient`() {
        val model =
            ScreenModelBuilder.of(
                mode = ObserveMode.VISUAL,
                windows = listOf(window(listOf(node("确定", act = listOf("click"))))),
                degenerate = true,
            )
        assertThat(model.quality.insufficient).isEqualTo(ScreenJsonWire.INSUFFICIENT_FEW_NODES)
    }

    @Test
    fun `zero interactive is insufficient for every mode`() {
        for (mode in ObserveMode.entries) {
            val model =
                ScreenModelBuilder.of(
                    mode = mode,
                    windows = listOf(window(listOf(node("设置")))),
                )
            assertThat(model.quality.insufficient).isEqualTo(ScreenJsonWire.INSUFFICIENT_NO_INTERACTIVE)
        }
    }

    @Test
    fun `suggest is next effective mode for any insufficient mode`() {
        val visual =
            ScreenModelBuilder.of(
                mode = ObserveMode.VISUAL,
                windows = listOf(window(listOf(node("设置")))),
                degenerate = true,
                effectiveModes = listOf(ObserveMode.A11Y, ObserveMode.VISUAL, ObserveMode.LLM),
            )
        assertThat(visual.quality.suggest).isEqualTo(ObserveMode.LLM)
        val a11y =
            ScreenModelBuilder.of(
                mode = ObserveMode.A11Y,
                windows = listOf(window(listOf(node("设置")))),
                noAppWindow = true,
                effectiveModes = listOf(ObserveMode.A11Y, ObserveMode.VISUAL),
            )
        assertThat(a11y.quality.insufficient).isEqualTo(ScreenJsonWire.INSUFFICIENT_NO_APP_WINDOW)
        assertThat(a11y.quality.suggest).isEqualTo(ObserveMode.VISUAL)
    }

    @Test
    fun `no uncertain field in quality`() {
        val quality =
            ScreenModelBuilder
                .of(mode = ObserveMode.VISUAL, windows = listOf(window(listOf(node("设置")))))
                .quality
        assertThat(Json.encodeToString(ScreenQuality.serializer(), quality)).doesNotContain("uncertain")
    }

    private fun window(kids: List<ScreenNode>): ScreenWindow =
        ScreenWindow(app = "com.example.app", page = "MainActivity", kids = kids)

    private fun node(
        text: String,
        act: List<String> = emptyList(),
    ): ScreenNode =
        ScreenNode(
            tag = "text",
            text = text,
            act = act,
            bounds = Rect(0, 0, 100, 50),
        )
}
