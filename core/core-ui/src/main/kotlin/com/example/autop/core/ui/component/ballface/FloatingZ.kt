/**
 * 睡眠时的浮动 z（多个 z 从小到大连续升起）。纯函数，按时刻算出每个 z 的大小、位置与透明度，便于单测。
 *
 * 每隔 [BallZStyle.intervalSec] 秒出现一个新 z，同时存在 [BallZStyle.count] 个，生命周期为两者之积。
 * 新出现的 z 小、靠近头部右上角；越往上飘越大，飘到顶端淡出。
 */
package com.example.autop.core.ui.component.ballface

/** 一个浮动 z 的绘制参数（设计单位）。[alpha] 为 0 到 1。 */
internal data class FloatingZ(
    val size: Float,
    val x: Float,
    val y: Float,
    val alpha: Float,
)

/** 新 z 的起点、每个生命周期内的位移与字号范围（设计单位）。 */
private const val Z_START_X: Float = 330f
private const val Z_START_Y: Float = -330f
private const val Z_DX: Float = 120f
private const val Z_DY: Float = 280f
private const val Z_SIZE_MIN: Float = 90f
private const val Z_SIZE_MAX: Float = 240f

/** 淡入结束的生命周期比例、淡出开始的比例。 */
private const val Z_FADE_IN_END: Float = 0.15f
private const val Z_FADE_OUT_START: Float = 0.7f

/** 透明度低于它的 z 不绘制。 */
private const val Z_MIN_ALPHA: Float = 0.01f

/**
 * 时刻 [t]（秒）的全部可见 z，按大小从小到大排序（小的先画，大的在上层）。
 * 同时存在 [BallZStyle.count] 个；透明度低于 [Z_MIN_ALPHA] 的不返回。
 */
internal fun floatingZsOf(
    t: Float,
    style: BallZStyle,
): List<FloatingZ> {
    val count = style.count.coerceAtLeast(1)
    val life = count * style.intervalSec
    val zs =
        (0 until count).map { i ->
            // 第 i 个 z 的出生时刻相差 i 个间隔；age 取模后落在 [0, life) 内。
            val age = (((t - i * style.intervalSec) % life) + life) % life
            val p = age / life
            FloatingZ(
                size = Z_SIZE_MIN + (Z_SIZE_MAX - Z_SIZE_MIN) * p,
                x = Z_START_X + Z_DX * p,
                y = Z_START_Y - Z_DY * p,
                alpha = alphaOf(p),
            )
        }
    return zs.filter { it.alpha >= Z_MIN_ALPHA }.sortedBy { it.size }
}

/** 生命周期比例 [p]（0 刚出现，1 消失）对应的透明度。 */
private fun alphaOf(p: Float): Float =
    when {
        p < Z_FADE_IN_END -> p / Z_FADE_IN_END
        p > Z_FADE_OUT_START -> ((1f - p) / (1f - Z_FADE_OUT_START)).coerceAtLeast(0f)
        else -> 1f
    }
