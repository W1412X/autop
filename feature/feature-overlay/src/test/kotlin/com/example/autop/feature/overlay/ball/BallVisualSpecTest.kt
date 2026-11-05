package com.example.autop.feature.overlay.ball

import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.overlay.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 悬浮球视觉差异矩阵单测（N185）：各状态的环/光晕/角标/图标参数与动效开关退化行为。 */
class BallVisualSpecTest {
    @Test
    fun `idle has no ring halo or badge`() {
        val spec = ballVisualSpec(BallMode.Idle, motionEnabled = true)
        assertThat(spec.ringTone).isNull()
        assertThat(spec.ringKind).isEqualTo(RingKind.NONE)
        assertThat(spec.haloKind).isEqualTo(HaloKind.NONE)
        assertThat(spec.badgeKind).isEqualTo(BadgeKind.NONE)
    }

    @Test
    fun `running draws progress ring with breath halo when motion on`() {
        val spec =
            ballVisualSpec(
                BallMode.Running(stepCount = 3, progress = 0.5f, toolCategory = ToolCategory.GESTURE),
                motionEnabled = true,
            )
        assertThat(spec.ringTone).isEqualTo(StatusTone.RUNNING)
        assertThat(spec.ringKind).isEqualTo(RingKind.PROGRESS)
        assertThat(spec.ringProgress).isEqualTo(0.5f)
        assertThat(spec.haloKind).isEqualTo(HaloKind.BREATH)
    }

    @Test
    fun `running without budget draws indeterminate ring`() {
        val spec =
            ballVisualSpec(
                BallMode.Running(stepCount = 3, progress = null, toolCategory = null),
                motionEnabled = true,
            )
        assertThat(spec.ringKind).isEqualTo(RingKind.INDETERMINATE)
    }

    @Test
    fun `motion off drops breath halo but keeps progress ring`() {
        val spec =
            ballVisualSpec(
                BallMode.Running(stepCount = 3, progress = 0.5f, toolCategory = null),
                motionEnabled = false,
            )
        assertThat(spec.haloKind).isEqualTo(HaloKind.NONE)
        assertThat(spec.ringKind).isEqualTo(RingKind.PROGRESS)
    }

    @Test
    fun `waiting answer pulses badge with motion and shows static dot without`() {
        val on = ballVisualSpec(BallMode.WaitingAnswer, motionEnabled = true)
        assertThat(on.ringTone).isEqualTo(StatusTone.WAITING)
        assertThat(on.ringKind).isEqualTo(RingKind.FULL)
        assertThat(on.haloKind).isEqualTo(HaloKind.PULSE)
        assertThat(on.badgeKind).isEqualTo(BadgeKind.PULSE)
        val off = ballVisualSpec(BallMode.WaitingAnswer, motionEnabled = false)
        assertThat(off.haloKind).isEqualTo(HaloKind.NONE)
        assertThat(off.badgeKind).isEqualTo(BadgeKind.DOT)
    }

    @Test
    fun `paused blocked and accessibility lost use full state rings`() {
        val paused = ballVisualSpec(BallMode.Paused, motionEnabled = true)
        assertThat(paused.ringTone).isEqualTo(StatusTone.PAUSED)
        assertThat(paused.ringKind).isEqualTo(RingKind.FULL)
        val blocked = ballVisualSpec(BallMode.Blocked, motionEnabled = true)
        assertThat(blocked.ringTone).isEqualTo(StatusTone.BLOCKED)
        val lost = ballVisualSpec(BallMode.AccessibilityLost, motionEnabled = true)
        assertThat(lost.ringTone).isEqualTo(StatusTone.BLOCKED)
    }

    @Test
    fun `accessibility lost dims center icon`() {
        val spec = ballVisualSpec(BallMode.AccessibilityLost, motionEnabled = true)
        assertThat(spec.iconAlpha).isLessThan(1f)
    }

    @Test
    fun `ball description follows mode`() {
        assertThat(ballDescriptionOf(BallMode.Idle)).isEqualTo(R.string.overlay_ball_description)
        assertThat(
            ballDescriptionOf(BallMode.Running(stepCount = 1, progress = null, toolCategory = null)),
        ).isEqualTo(R.string.overlay_ball_running_description)
        assertThat(ballDescriptionOf(BallMode.WaitingAnswer)).isEqualTo(R.string.overlay_ball_waiting_description)
        assertThat(ballDescriptionOf(BallMode.Paused)).isEqualTo(R.string.overlay_ball_paused_description)
        assertThat(ballDescriptionOf(BallMode.Blocked)).isEqualTo(R.string.overlay_ball_blocked_description)
        assertThat(ballDescriptionOf(BallMode.AccessibilityLost))
            .isEqualTo(R.string.overlay_ball_accessibility_lost_description)
    }
}
