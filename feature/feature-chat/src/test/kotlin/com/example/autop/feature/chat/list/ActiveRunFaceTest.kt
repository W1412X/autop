package com.example.autop.feature.chat.list

import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.core.ui.component.ballface.BallFaceScene
import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveRunFaceTest {
    @Test
    fun `起跑场景显示起跑脸，其余场景显示运行脸`() {
        assertEquals(BallFaceMood.RUNNING_START, activeRunFaceOf(BallFaceScene.STARTING))
        assertEquals(BallFaceMood.RUNNING, activeRunFaceOf(BallFaceScene.SAMPLING))
        assertEquals(BallFaceMood.RUNNING, activeRunFaceOf(BallFaceScene.COMPACTING))
    }
}
