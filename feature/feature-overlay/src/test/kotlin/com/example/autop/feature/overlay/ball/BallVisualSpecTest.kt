package com.example.autop.feature.overlay.ball

import com.example.autop.agent.RunState
import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.core.ui.component.ballface.BallFaceScene
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 球状态 → 表情状态与表演场景的映射（颜色不承载状态，只看表情口径）。 */
class BallVisualSpecTest {
    @Test
    fun `fixed ball modes map to one fixed mood each`() {
        assertThat(ballFaceMoodOf(BallMode.Idle)).isEqualTo(BallFaceMood.IDLE)
        assertThat(ballFaceMoodOf(BallMode.WaitingAnswer)).isEqualTo(BallFaceMood.WAITING)
        assertThat(ballFaceMoodOf(BallMode.Paused)).isEqualTo(BallFaceMood.PAUSED)
        assertThat(ballFaceMoodOf(BallMode.Blocked)).isEqualTo(BallFaceMood.BLOCKED)
        assertThat(ballFaceMoodOf(BallMode.AccessibilityLost)).isEqualTo(BallFaceMood.LOST)
        assertThat(ballFaceMoodOf(BallMode.Recording)).isEqualTo(BallFaceMood.RECORDING)
    }

    @Test
    fun `running in the starting phase uses the start mood`() {
        val mode = running(RunState.STARTING, null)
        assertThat(ballFaceMoodOf(mode)).isEqualTo(BallFaceMood.RUNNING_START)
        assertThat(ballFaceSceneOf(mode)).isEqualTo(BallFaceScene.STARTING)
    }

    @Test
    fun `running in sampling and compacting phases`() {
        assertThat(ballFaceMoodOf(running(RunState.SAMPLING, null))).isEqualTo(BallFaceMood.RUNNING)
        assertThat(ballFaceSceneOf(running(RunState.SAMPLING, null))).isEqualTo(BallFaceScene.SAMPLING)
        assertThat(ballFaceSceneOf(running(RunState.COMPACTING, null))).isEqualTo(BallFaceScene.COMPACTING)
    }

    @Test
    fun `tool categories pick their own scene`() {
        val expected =
            mapOf(
                ToolCategory.GESTURE to BallFaceScene.GESTURE,
                ToolCategory.TEXT to BallFaceScene.TEXT,
                ToolCategory.VISION to BallFaceScene.VISION,
                ToolCategory.APP to BallFaceScene.APP,
                ToolCategory.OTHER to BallFaceScene.SAMPLING,
            )
        for ((category, scene) in expected) {
            assertThat(ballFaceSceneOf(running(RunState.EXECUTING_TOOLS, category))).isEqualTo(scene)
        }
        assertThat(ballFaceSceneOf(running(RunState.EXECUTING_TOOLS, null))).isEqualTo(BallFaceScene.SAMPLING)
    }

    @Test
    fun `several running sessions draw from the mixed pool`() {
        val mode = running(RunState.EXECUTING_TOOLS, ToolCategory.GESTURE)
        assertThat(ballFaceSceneOf(mode, sessionCount = 3)).isEqualTo(BallFaceScene.MIXED)
        assertThat(ballFaceSceneOf(mode, sessionCount = 1)).isEqualTo(BallFaceScene.GESTURE)
    }

    private fun running(
        state: RunState,
        category: ToolCategory?,
    ): BallMode.Running = BallMode.Running(stepCount = 1, toolCategory = category, state = state, seed = 1L)
}
