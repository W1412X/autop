package com.example.autop.feature.overlay.ball

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BallMenuLogicTest {
    @Test
    fun `menu lists sessions first then new task and open app`() {
        assertThat(deriveBallMenu(toolTestEnabled = true, flowsEnabled = true))
            .isEqualTo(
                listOf(
                    BallMenuAction.LIST,
                    BallMenuAction.CREATE_TASK,
                    BallMenuAction.OPEN_APP,
                    BallMenuAction.RECORD,
                    BallMenuAction.TOOL_TEST,
                ),
            )
    }

    @Test
    fun `beta and debug switches only hide their own entries`() {
        assertThat(deriveBallMenu(toolTestEnabled = false, flowsEnabled = true))
            .isEqualTo(
                listOf(
                    BallMenuAction.LIST,
                    BallMenuAction.CREATE_TASK,
                    BallMenuAction.OPEN_APP,
                    BallMenuAction.RECORD,
                ),
            )
        assertThat(deriveBallMenu(toolTestEnabled = false, flowsEnabled = false))
            .isEqualTo(listOf(BallMenuAction.LIST, BallMenuAction.CREATE_TASK, BallMenuAction.OPEN_APP))
    }

    @Test
    fun `arc opens toward screen interior with first button on top`() {
        // 球心在左半屏：开口朝右，两个按钮分列 ±35°、半径 100。
        val spots = arcSpotsOf(ballCenterX = 200f, ballCenterY = 600f, radiusPx = 100f)
        assertThat(spots[0].x).isGreaterThan(200f)
        assertThat(spots[1].x).isGreaterThan(200f)
        assertThat(spots[0].y).isLessThan(600f)
        assertThat(spots[1].y).isGreaterThan(600f)
    }

    @Test
    fun `arc opens left when ball is on the right half`() {
        // 球心在右半屏：开口朝左，序号 0 仍在上。
        val spots = arcSpotsOf(ballCenterX = 900f, ballCenterY = 600f, radiusPx = 100f)
        assertThat(spots[0].x).isLessThan(900f)
        assertThat(spots[1].x).isLessThan(900f)
        assertThat(spots[0].y).isLessThan(600f)
        assertThat(spots[1].y).isGreaterThan(600f)
    }

    @Test
    fun `arc button boxes clamp inside the screen`() {
        // 球心贴上缘（y=10）：上方按钮圆心钳到圆半径（24），下方整块按钮不越底缘。
        val spots = arcSpotsOf(ballCenterX = 200f, ballCenterY = 10f, radiusPx = 200f)
        assertThat(spots[0].y).isEqualTo(24f)
        // 整块高 76（圆 48 + 间距 4 + 文字 24）：圆心 ≤ 2400-76+24。
        assertThat(spots[1].y).isAtMost(2400f - 76f + 24f)
    }

    private fun arcSpotsOf(
        ballCenterX: Float,
        ballCenterY: Float,
        radiusPx: Float,
    ): List<MenuSpot> =
        arcSpots(
            actions = listOf(BallMenuAction.CREATE_TASK, BallMenuAction.OPEN_APP),
            ballCenterX = ballCenterX,
            ballCenterY = ballCenterY,
            radiusPx = radiusPx,
            screenWidthPx = 1080f,
            screenHeightPx = 2400f,
            buttonSizePx = 48f,
            itemWidthPx = 76f,
            itemHeightPx = 76f,
        )
}
