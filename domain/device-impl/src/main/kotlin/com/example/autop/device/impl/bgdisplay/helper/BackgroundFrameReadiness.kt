package com.example.autop.device.impl.bgdisplay.helper

import android.graphics.Bitmap
import android.graphics.Color

private const val SAMPLE_COLUMNS = 24
private const val SAMPLE_ROWS = 48
private const val BLACK_LUMA = 12
private const val RED_WEIGHT = 30
private const val GREEN_WEIGHT = 59
private const val BLUE_WEIGHT = 11
private const val LUMA_DIVISOR = 100
private const val BLACK_PERCENT = 98

/** DSH-go 的首帧策略：黑帧只代表画面未就绪，不推断 FLAG_SECURE。 */
internal object BackgroundFrameReadiness {
    const val CAPTURE_TIMEOUT_MS = 2_500L
    const val RETRY_WAIT_MS = 200L

    enum class Next { READY, RETRY, NOT_READY }

    fun next(
        black: Boolean,
        attempt: Int,
        remainingMs: Long,
    ): Next =
        when {
            !black -> Next.READY
            attempt == 0 && remainingMs > 0 -> Next.RETRY
            else -> Next.NOT_READY
        }

    fun retryWaitMs(remainingMs: Long): Long = minOf(RETRY_WAIT_MS, remainingMs.coerceAtLeast(0))
}

/** 与 DSH-go 相同的近黑判定：24×48 网格采样，亮度 <12 且占比至少 98%。 */
internal fun Bitmap.isMostlyBlack(): Boolean {
    val stepX = (width / SAMPLE_COLUMNS).coerceAtLeast(1)
    val stepY = (height / SAMPLE_ROWS).coerceAtLeast(1)
    var dark = 0
    var total = 0
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val color = getPixel(x, y)
            val luma =
                (
                    Color.red(color) * RED_WEIGHT +
                        Color.green(color) * GREEN_WEIGHT +
                        Color.blue(color) * BLUE_WEIGHT
                ) / LUMA_DIVISOR
            if (luma < BLACK_LUMA) dark++
            total++
            x += stepX
        }
        y += stepY
    }
    return total > 0 && dark * LUMA_DIVISOR / total >= BLACK_PERCENT
}
