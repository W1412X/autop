package com.example.autop.feature.flows.record

import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.flow.FlowRunStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 流程运行状态 → 执行记录页表情（P1-12）。 */
class FlowRunFaceTest {
    @Test
    fun `运行暂停成功失败各用自己的表情`() {
        assertThat(flowRunFaceOf(FlowRunStatus.RUNNING)).isEqualTo(BallFaceMood.RUNNING)
        assertThat(flowRunFaceOf(FlowRunStatus.PAUSED)).isEqualTo(BallFaceMood.PAUSED)
        assertThat(flowRunFaceOf(FlowRunStatus.SUCCEEDED)).isEqualTo(BallFaceMood.SUCCESS)
        assertThat(flowRunFaceOf(FlowRunStatus.FAILED)).isEqualTo(BallFaceMood.FAILURE)
    }

    @Test
    fun `单步停驻按暂停表情，取消保持平静`() {
        assertThat(flowRunFaceOf(FlowRunStatus.RUNNING, paused = true)).isEqualTo(BallFaceMood.PAUSED)
        assertThat(flowRunFaceOf(FlowRunStatus.CANCELLED)).isEqualTo(BallFaceMood.PAUSED)
    }
}
