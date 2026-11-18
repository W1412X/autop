package com.example.autop.feature.overlay.ball

import com.example.autop.core.ui.component.ballface.BallAmbientMode

/** 环境快照：睡眠等环境状态与网络是否断开。 */
internal data class BallAmbientSnapshot(
    val ambient: BallAmbientMode,
    val offline: Boolean,
)
