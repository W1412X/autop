package com.example.autop.task.impl.notify

import com.example.autop.task.RunStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 通知大图标表情映射（P1-15）：只按结局选帧，取消/中断保持平静。 */
class NotifyFaceTest {
    @Test
    fun `成功与失败结局各用自己的表情`() {
        assertThat(notifyFaceOf(RunStatus.SUCCEEDED)).isEqualTo(NotifyFace.SUCCESS)
        assertThat(notifyFaceOf(RunStatus.FAILED)).isEqualTo(NotifyFace.FAILURE)
        assertThat(notifyFaceOf(RunStatus.BUDGET_EXHAUSTED)).isEqualTo(NotifyFace.FAILURE)
        assertThat(notifyFaceOf(RunStatus.BREAKER_STOPPED)).isEqualTo(NotifyFace.FAILURE)
        assertThat(notifyFaceOf(RunStatus.EXPIRED)).isEqualTo(NotifyFace.FAILURE)
    }

    @Test
    fun `取消中断与未知状态回空闲脸`() {
        assertThat(notifyFaceOf(RunStatus.CANCELLED)).isEqualTo(NotifyFace.IDLE)
        assertThat(notifyFaceOf(RunStatus.INTERRUPTED)).isEqualTo(NotifyFace.IDLE)
        assertThat(notifyFaceOf(null)).isEqualTo(NotifyFace.IDLE)
    }
}
