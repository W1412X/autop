/**
 * 小脸表情的最短驻留（ADR-0045）：表情切换后至少停留 [holdMs]，避免状态连续快速变化时表情一闪而过。
 *
 * - 只有表情键（[HeldFace]）变化才计时。同一张脸上的计数、颜色、形态变化不换脸，也不重新计时。
 * - 等待回答、拦截、无障碍断开（[HeldFace.urgent]）进入时立即显示；离开它们仍按驻留计时。
 * - 时长用单调时钟度量（[monotonicMillis]），不用墙钟，系统调整时间不会打乱驻留。
 */
package com.example.autop.core.ui.component.ballface

import kotlin.time.TimeSource

/** 决定小脸外观的输入：同一个键表示同一张脸。固定态只看情绪；表演态还看场景与种子。 */
internal data class HeldFace(
    val mood: BallFaceMood,
    val scene: BallFaceScene,
    val seed: Long,
) {
    /** 需要立即处理的状态：进入时不等待驻留。 */
    val urgent: Boolean
        get() = mood == BallFaceMood.WAITING || mood == BallFaceMood.BLOCKED || mood == BallFaceMood.LOST
}

/** 把渲染参数归一成表情键：固定态忽略场景与种子，避免无关字段变化引起换脸。 */
internal fun heldFaceOf(
    mood: BallFaceMood,
    scene: BallFaceScene,
    seed: Long,
): HeldFace =
    if (fixedBallFaceExpressionOf(mood) == null) {
        HeldFace(mood, scene, seed)
    } else {
        HeldFace(mood, BallFaceScene.SAMPLING, 0L)
    }

/** 单调时钟（毫秒）：只用于度量驻留时长。 */
private val monotonicStart = TimeSource.Monotonic.markNow()

internal val monotonicMillis: () -> Long = { monotonicStart.elapsedNow().inWholeMilliseconds }

/** 驻留计时器：记录上一张显示中的脸与显示时刻。 */
internal class FaceDwell(
    private val nowMs: () -> Long = monotonicMillis,
) {
    private var hasShown = false
    private var shownKey: HeldFace? = null
    private var shownAtMs: Long = 0L

    /** 显示 [next] 前还需等待的毫秒数；0 表示立即显示。 */
    fun waitMs(
        next: HeldFace,
        holdMs: Long,
    ): Long {
        if (!hasShown || next.urgent || next == shownKey) return 0L
        return (shownAtMs + holdMs - nowMs()).coerceAtLeast(0L)
    }

    /** 记录已显示的脸；表情键变化时从此刻重新计时。 */
    fun onShown(next: HeldFace) {
        if (hasShown && next == shownKey) return
        hasShown = true
        shownKey = next
        shownAtMs = nowMs()
    }
}
