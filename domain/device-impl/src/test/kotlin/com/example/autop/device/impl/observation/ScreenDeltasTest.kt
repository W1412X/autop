/**
 * 屏幕变化结论（agent-harness-overhaul WP2.11）：五条规则各一例，
 * 页面变化优先于观测方式切换。
 */
package com.example.autop.device.impl.observation

import com.example.autop.agent.ScreenDeltaKind
import com.example.autop.agent.ScreenDeltaReasons
import com.example.autop.core.config.DeviceChangeSection
import com.example.autop.device.Observation
import com.example.autop.device.ObservationMode
import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenQuality
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType
import com.example.autop.device.core.ScreenNodeMatcher
import com.example.autop.device.core.SerializedScreen
import com.example.autop.device.impl.RecordedObservation
import com.example.autop.device.impl.vision.FrameSignature
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenDeltasTest {
    private val cfg = DeviceChangeSection()

    @Test
    fun `no previous observation is UNKNOWN no_baseline`() {
        val delta = ScreenDeltas.of(null, prepared(ObserveMode.A11Y), null, cfg)
        assertThat(delta.kind).isEqualTo(ScreenDeltaKind.UNKNOWN)
        assertThat(delta.reason).isEqualTo(ScreenDeltaReasons.NO_BASELINE)
    }

    @Test
    fun `different activity is CHANGED page`() {
        val delta =
            ScreenDeltas.of(
                recorded(ObserveMode.A11Y, activity = "$APP.MainActivity"),
                prepared(ObserveMode.A11Y, activity = "$APP.OtherActivity"),
                null,
                cfg,
            )
        assertThat(delta.kind).isEqualTo(ScreenDeltaKind.CHANGED)
        assertThat(delta.reason).isEqualTo(ScreenDeltaReasons.PAGE)
    }

    @Test
    fun `different mode on same page is CHANGED mode_switch`() {
        val delta =
            ScreenDeltas.of(
                recorded(ObserveMode.A11Y),
                prepared(ObserveMode.VISUAL),
                null,
                cfg,
            )
        assertThat(delta.kind).isEqualTo(ScreenDeltaKind.CHANGED)
        assertThat(delta.reason).isEqualTo(ScreenDeltaReasons.MODE_SWITCH)
    }

    @Test
    fun `same llm frames are UNCHANGED frame`() {
        val frame = FrameSignature(hash = 7L, gray = IntArray(64 * 64))
        val delta =
            ScreenDeltas.of(
                recorded(ObserveMode.LLM, frame = frame),
                prepared(ObserveMode.LLM, frame = FrameSignature(hash = 7L, gray = IntArray(64 * 64))),
                null,
                cfg,
            )
        assertThat(delta.kind).isEqualTo(ScreenDeltaKind.UNCHANGED)
        assertThat(delta.reason).isEqualTo(ScreenDeltaReasons.FRAME)
    }

    @Test
    fun `llm without frames is UNKNOWN no_baseline`() {
        val delta = ScreenDeltas.of(recorded(ObserveMode.LLM), prepared(ObserveMode.LLM), null, cfg)
        assertThat(delta.kind).isEqualTo(ScreenDeltaKind.UNKNOWN)
        assertThat(delta.reason).isEqualTo(ScreenDeltaReasons.NO_BASELINE)
    }

    @Test
    fun `empty match is UNCHANGED same and non-empty is CHANGED content`() {
        val empty =
            ScreenDeltas.of(
                recorded(ObserveMode.A11Y),
                prepared(ObserveMode.A11Y),
                ScreenNodeMatcher.Result(emptyList(), emptyList(), emptyList()),
                cfg,
            )
        assertThat(empty.kind).isEqualTo(ScreenDeltaKind.UNCHANGED)
        assertThat(empty.reason).isEqualTo(ScreenDeltaReasons.SAME)
        val changed = ScreenDeltas.of(recorded(ObserveMode.A11Y), prepared(ObserveMode.A11Y), resultOfOneAdded(), cfg)
        assertThat(changed.kind).isEqualTo(ScreenDeltaKind.CHANGED)
        assertThat(changed.reason).isEqualTo(ScreenDeltaReasons.CONTENT)
    }

    @Test
    fun `page change wins over mode switch`() {
        val delta =
            ScreenDeltas.of(
                recorded(ObserveMode.A11Y, activity = "$APP.MainActivity"),
                prepared(ObserveMode.VISUAL, activity = "$APP.OtherActivity"),
                null,
                cfg,
            )
        assertThat(delta.reason).isEqualTo(ScreenDeltaReasons.PAGE)
    }

    private fun resultOfOneAdded(): ScreenNodeMatcher.Result =
        ScreenNodeMatcher.Result(
            added =
                listOf(
                    com.example.autop.device
                        .ScreenNode(tag = "text", text = "新", bounds = Rect(0, 0, 10, 10)),
                ),
            removed = emptyList(),
            changed = emptyList(),
        )

    private fun prepared(
        mode: ObserveMode,
        activity: String = "$APP.MainActivity",
        frame: FrameSignature? = null,
    ): Prepared = Prepared(mode, snapshot(activity), screenOf(mode), frame)

    private fun recorded(
        mode: ObserveMode,
        activity: String = "$APP.MainActivity",
        frame: FrameSignature? = null,
    ): RecordedObservation =
        RecordedObservation(
            mode = mode,
            selection =
                com.example.autop.device.impl.ObserveSelection
                    .Explicit(mode),
            screen = screenOf(mode),
            snapshot = snapshot(activity),
            frame = frame,
        )

    private fun screenOf(mode: ObserveMode): SerializedScreen {
        val model = ScreenModel(mode = mode, windows = emptyList(), quality = ScreenQuality(0, 0))
        return SerializedScreen(
            Observation(
                text = "{}",
                mode = ObservationMode.FULL,
                observeMode = mode,
                fingerprint = "fp",
                packageName = APP,
                activity = "$APP.MainActivity",
                summaryLine = "app",
                interactiveCount = 0,
                nodeCount = 0,
                semanticPoor = false,
            ),
            model,
        )
    }

    private fun snapshot(activity: String): ScreenSnapshot =
        ScreenSnapshot(
            capturedAt = 0L,
            windows =
                listOf(
                    WindowSnapshot(
                        1,
                        WindowType.APPLICATION,
                        0,
                        APP,
                        "示例",
                        Rect(0, 0, 1080, 1920),
                        true,
                        true,
                        rootIdx = null,
                    ),
                ),
            nodes = emptyList(),
            foregroundPackage = APP,
            activity = activity,
            screenWidth = 1080,
            screenHeight = 1920,
            rotation = 0,
            keyguardLocked = false,
            contentArea = Rect(0, 0, 1080, 1920),
            truncated = false,
        )

    private companion object {
        const val APP = "com.example.app"
    }
}
