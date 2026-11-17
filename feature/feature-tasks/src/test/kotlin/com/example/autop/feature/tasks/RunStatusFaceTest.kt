package com.example.autop.feature.tasks

import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Run 状态名 → 详情页表情（P1-11）。 */
class RunStatusFaceTest {
    @Test
    fun `运行等待暂停按状态取表情`() {
        assertThat(runStatusFaceOf("RUNNING")).isEqualTo(BallFaceMood.RUNNING)
        assertThat(runStatusFaceOf("QUEUED")).isEqualTo(BallFaceMood.WAITING)
        assertThat(runStatusFaceOf("WAITING_USER")).isEqualTo(BallFaceMood.WAITING)
        assertThat(runStatusFaceOf("PAUSED")).isEqualTo(BallFaceMood.PAUSED)
        assertThat(runStatusFaceOf("BLOCKED")).isEqualTo(BallFaceMood.BLOCKED)
    }

    @Test
    fun `成功失败用瞬时表情，取消类保持平静`() {
        assertThat(runStatusFaceOf("SUCCEEDED")).isEqualTo(BallFaceMood.SUCCESS)
        assertThat(runStatusFaceOf("FAILED")).isEqualTo(BallFaceMood.FAILURE)
        assertThat(runStatusFaceOf("BUDGET_EXHAUSTED")).isEqualTo(BallFaceMood.FAILURE)
        assertThat(runStatusFaceOf("CANCELLED")).isEqualTo(BallFaceMood.PAUSED)
        assertThat(runStatusFaceOf(null)).isEqualTo(BallFaceMood.IDLE)
    }
}
