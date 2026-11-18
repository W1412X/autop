package com.example.autop.core.ui.component.ballface

/**
 * 常态池的一张脸：表情与它在给定上下文下的权重（0 表示不在池中）。
 */
internal data class IdleEntry(
    val expression: BallFaceExpression,
    val weightOf: (BallPoolContext) -> Int,
)
