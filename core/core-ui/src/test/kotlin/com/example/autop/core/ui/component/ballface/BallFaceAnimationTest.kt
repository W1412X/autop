package com.example.autop.core.ui.component.ballface

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 表情过渡的单测：插值端点、形态混合、中途改向不跳变、动效关闭直接落帧。 */
class BallFaceAnimationTest {
    @Test
    fun `easing starts and ends at the endpoints and never decreases`() {
        assertThat(easeInOut(0f)).isWithin(1e-6f).of(0f)
        assertThat(easeInOut(1f)).isWithin(1e-6f).of(1f)
        var previous = 0f
        for (i in 0..100) {
            val v = easeInOut(i / 100f)
            assertThat(v).isAtLeast(previous)
            previous = v
        }
    }

    @Test
    fun `the first frame does not transition`() {
        val animator = FaceAnimator()
        animator.setTarget(BallFaceExpressions.calm, null, animate = true)
        val frame = animator.frameAt(1f, motion = true)
        assertThat(frame.left.from).isNull()
    }

    @Test
    fun `switching eye forms blends the capsule from the old shape`() {
        val animator = FaceAnimator()
        animator.setTarget(BallFaceExpressions.calm, null, animate = true)
        animator.frameAt(1.0f, motion = true)
        animator.setTarget(BallFaceExpressions.thinkClosed, null, animate = true)
        animator.frameAt(1.1f, motion = true)
        val mid = animator.frameAt(1.15f, motion = true)
        assertThat(mid.left.form).isEqualTo(BallEyeForm.CLOSED)
        assertThat(mid.left.from).isEqualTo(BallEyeForm.OPEN)
        assertThat(mid.left.mix).isGreaterThan(0f)
        assertThat(mid.left.mix).isLessThan(1f)
    }

    @Test
    fun `transition ends exactly on the target frame`() {
        val animator = FaceAnimator()
        animator.setTarget(BallFaceExpressions.calm, null, animate = true)
        animator.frameAt(1.0f, motion = true)
        animator.setTarget(BallFaceExpressions.thinkClosed, null, animate = true)
        animator.frameAt(1.0f, motion = true)
        val settled = animator.frameAt(1.5f, motion = true)
        assertThat(settled).isEqualTo(ballFaceFrameOf(BallFaceExpressions.thinkClosed, 1.5f, motion = true))
    }

    @Test
    fun `retargeting mid transition continues from the displayed frame`() {
        val animator = FaceAnimator()
        animator.setTarget(BallFaceExpressions.calm, null, animate = true)
        animator.frameAt(1.0f, motion = true)
        animator.setTarget(BallFaceExpressions.waiting, null, animate = true)
        val displayed = animator.frameAt(1.1f, motion = true)
        animator.setTarget(BallFaceExpressions.focus, null, animate = true)
        val afterRetarget = animator.frameAt(1.1f, motion = true)
        // 同一时刻改向：第一帧必须与刚才显示的一致，不能从旧表情的目标值跳过去。
        assertThat(afterRetarget.gazeX).isWithin(1e-3f).of(displayed.gazeX)
        assertThat(afterRetarget.gazeY).isWithin(1e-3f).of(displayed.gazeY)
        assertThat(afterRetarget.satellite.x).isWithin(1e-3f).of(displayed.satellite.x)
    }

    @Test
    fun `numeric values move monotonically between the endpoints`() {
        val animator = FaceAnimator()
        animator.setTarget(BallFaceExpressions.calm, null, animate = true)
        val from = animator.frameAt(1.0f, motion = true)
        animator.setTarget(BallFaceExpressions.appLook, null, animate = true)
        animator.frameAt(1.1f, motion = true)
        val to = ballFaceFrameOf(BallFaceExpressions.appLook, 1.2f, motion = true)
        val mid = animator.frameAt(1.2f, motion = true)
        val between =
            (mid.gazeY >= minOf(from.gazeY, to.gazeY) - 1e-3f) && (mid.gazeY <= maxOf(from.gazeY, to.gazeY) + 1e-3f)
        assertThat(between).isTrue()
    }

    @Test
    fun `motion off snaps straight to the target`() {
        val animator = FaceAnimator()
        animator.setTarget(BallFaceExpressions.calm, null, animate = true)
        animator.frameAt(1.0f, motion = false)
        animator.setTarget(BallFaceExpressions.thinkClosed, null, animate = true)
        val frame = animator.frameAt(1.05f, motion = false)
        assertThat(frame).isEqualTo(ballFaceFrameOf(BallFaceExpressions.thinkClosed, 1.05f, motion = false))
        assertThat(frame.left.from).isNull()
    }

    @Test
    fun `changing the digit count crossfades the satellite content`() {
        val animator = FaceAnimator()
        animator.setTarget(BallFaceExpressions.thinkUp, "2", animate = true)
        animator.frameAt(1.0f, motion = true)
        animator.setTarget(BallFaceExpressions.thinkUp, "3", animate = true)
        val mid = animator.frameAt(1.05f, motion = true)
        assertThat(mid.satellite.content).isEqualTo(SatelliteContent.Digits("3"))
        assertThat(mid.satellite.contentFrom).isEqualTo(SatelliteContent.Digits("2"))
    }

    @Test
    fun `the circle body never blends because it is not part of the frame`() {
        // 圆盘不在逐帧状态里：过渡中只有眼睛和卫星内容带来源，身体参数只有数值插值。
        val frame = ballFaceFrameOf(BallFaceExpressions.calm, 1f, motion = true)
        assertThat(frame.left.from).isNull()
        assertThat(frame.satellite.contentFrom).isNull()
    }
}
