package com.example.autop.core.ui.component.ballface

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sqrt

/**
 * 表情参数（设计稿 EXPRS）、固定态映射、帧计算与符号几何的单测。
 * 符号几何用 Compose Path（底层为 android.graphics.Path），所以类上标注了 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BallFaceFrameTest {
    @Test
    fun `eye baseline matches the design preview`() {
        assertThat(BallFaceSpec.EYE_OFFSET_Y).isEqualTo(526f)
    }

    @Test
    fun `fixed moods map to one expression and performance moods to none`() {
        assertThat(fixedBallFaceExpressionOf(BallFaceMood.IDLE)).isNull()
        assertThat(fixedBallFaceExpressionOf(BallFaceMood.WAITING)).isEqualTo(BallFaceExpressions.waiting)
        assertThat(fixedBallFaceExpressionOf(BallFaceMood.PAUSED)).isEqualTo(BallFaceExpressions.paused)
        assertThat(fixedBallFaceExpressionOf(BallFaceMood.BLOCKED)).isEqualTo(BallFaceExpressions.blocked)
        assertThat(fixedBallFaceExpressionOf(BallFaceMood.LOST)).isEqualTo(BallFaceExpressions.lost)
        assertThat(fixedBallFaceExpressionOf(BallFaceMood.RECORDING)).isEqualTo(BallFaceExpressions.recording)
        assertThat(fixedBallFaceExpressionOf(BallFaceMood.SUCCESS)).isEqualTo(BallFaceExpressions.success)
        assertThat(fixedBallFaceExpressionOf(BallFaceMood.FAILURE)).isEqualTo(BallFaceExpressions.failure)
        assertThat(fixedBallFaceExpressionOf(BallFaceMood.RUNNING)).isNull()
        assertThat(fixedBallFaceExpressionOf(BallFaceMood.RUNNING_START)).isNull()
    }

    @Test
    fun `fixed faces rest the satellite at 1 9x and performance faces orbit at 470 with 1 75x`() {
        val waiting = ballFaceFrameOf(BallFaceExpressions.waiting, STATIC_TIME_SEC, motion = false)
        assertThat(waiting.satellite.degrees).isEqualTo(45f)
        assertThat(waiting.satellite.scale).isWithin(1e-4f).of(1.9f)
        val start = BallFaceExpressions.startWide
        assertThat(start.satelliteOrbit).isEqualTo(470f)
        assertThat(start.satelliteScale).isWithin(1e-4f).of(1.75f)
    }

    @Test
    fun `satellite stays on its orbit radius`() {
        val rest = ballFaceFrameOf(BallFaceExpressions.waiting, STATIC_TIME_SEC, motion = false)
        assertThat(sqrt(rest.satellite.x * rest.satellite.x + rest.satellite.y * rest.satellite.y))
            .isWithin(0.5f)
            .of(537f)
        val perform = ballFaceFrameOf(BallFaceExpressions.focus, 0f, motion = false)
        assertThat(sqrt(perform.satellite.x * perform.satellite.x + perform.satellite.y * perform.satellite.y))
            .isWithin(0.5f)
            .of(470f)
    }

    @Test
    fun `resting satellite sits at the upper right`() {
        val frame = ballFaceFrameOf(BallFaceExpressions.waiting, STATIC_TIME_SEC, motion = false)
        assertThat(frame.satellite.x).isGreaterThan(0f)
        assertThat(frame.satellite.y).isLessThan(0f)
    }

    @Test
    fun `orbit angle advances one turn per period`() {
        val e = BallFaceExpressions.startWide
        assertThat(ballFaceFrameOf(e, 0f, motion = true).satellite.degrees).isWithin(1e-3f).of(45f)
        assertThat(ballFaceFrameOf(e, 1.3f, motion = true).satellite.degrees).isWithin(1e-3f).of(225f)
    }

    @Test
    fun `blink closes blinkable eyes only in motion`() {
        val blinkTime = 5.1f
        val moving = ballFaceFrameOf(BallFaceExpressions.visionStare, blinkTime, motion = true)
        assertThat(moving.left.form).isEqualTo(BallEyeForm.CLOSED)
        assertThat(moving.right.form).isEqualTo(BallEyeForm.CLOSED)
        val still = ballFaceFrameOf(BallFaceExpressions.visionStare, blinkTime, motion = false)
        assertThat(still.left.form).isEqualTo(BallEyeForm.WIDE)
    }

    @Test
    fun `motion off draws a static frame without breath pulse or shake`() {
        val failure = ballFaceFrameOf(BallFaceExpressions.failure, STATIC_TIME_SEC, motion = false)
        assertThat(failure.breath).isEqualTo(1f)
        assertThat(failure.shake).isEqualTo(0f)
        assertThat(failure.satellite.scale).isWithin(1e-4f).of(1.9f)
    }

    @Test
    fun `failure shakes and recording pulses only in motion`() {
        val shaking = ballFaceFrameOf(BallFaceExpressions.failure, 0.1f, motion = true)
        assertThat(shaking.shake).isNotEqualTo(0f)
        val pulsing = ballFaceFrameOf(BallFaceExpressions.recording, 0.6f, motion = true)
        assertThat(pulsing.satellite.scale).isGreaterThan(1.9f)
    }

    @Test
    fun `lost satellite drifts while the other faces hold still`() {
        val lost = ballFaceFrameOf(BallFaceExpressions.lost, 1f, motion = true)
        assertThat(lost.satellite.x).isNotEqualTo(0f)
        assertThat(lost.satellite.y).isNotEqualTo(0f)
        assertThat(lost.tilt).isNotEqualTo(BallFaceExpressions.lost.tilt)
    }

    @Test
    fun `follow satellite gaze looks toward the satellite`() {
        val focus = ballFaceFrameOf(BallFaceExpressions.focus, 0f, motion = true)
        assertThat(focus.gazeX).isGreaterThan(0f)
        assertThat(focus.gazeY).isLessThan(6f)
    }

    @Test
    fun `digits replace the satellite symbol while glyphs stay otherwise`() {
        val frame = ballFaceFrameOf(BallFaceExpressions.focus, 0f, motion = false, digits = "3")
        assertThat(frame.satellite.content).isEqualTo(SatelliteContent.Digits("3"))
        val glyph = ballFaceFrameOf(BallFaceExpressions.focus, 0f, motion = false)
        assertThat(glyph.satellite.content).isEqualTo(SatelliteContent.Glyph(BallFaceSymbol.ELLIPSIS))
    }

    @Test
    fun `symbols carry geometry and the anger glyph keeps its design transform`() {
        for (symbol in BallFaceSymbol.entries) {
            val glyph = ballGlyphOf(symbol)
            if (symbol == BallFaceSymbol.NONE) {
                assertThat(glyph.parts).isEmpty()
            } else {
                assertThat(glyph.parts).isNotEmpty()
            }
        }
        val anger = ballGlyphOf(BallFaceSymbol.ANGER)
        assertThat(anger.scale).isWithin(1e-6f).of(0.32f)
        assertThat(anger.translateX).isWithin(1e-6f).of(-512f)
        assertThat(anger.translateY).isWithin(1e-6f).of(-512f)
    }

    @Test
    fun `transition lasts the design crossfade time`() {
        assertThat(BallFaceDirector.CROSSFADE_MS).isEqualTo(200)
    }
}
