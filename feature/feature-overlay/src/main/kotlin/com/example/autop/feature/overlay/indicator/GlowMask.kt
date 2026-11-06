/**
 * 边缘光带的遮罩位图（ADR-0014）：一圈贴着屏幕边缘的圆角光带，经高斯模糊后得到连续的渐变，
 * 没有分层的台阶。只在屏幕尺寸或参数变化时生成一次；每帧只用旋转的主题色扫描渐变给它上色。
 */
package com.example.autop.feature.overlay.indicator

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** 遮罩位图相对窗口像素的缩小倍数：模糊在小图上做更快，放大绘制时本身就平滑。 */
private const val MASK_DOWNSCALE: Int = 4

/** 光带线宽、模糊半径占 [buildGlowMask] 的 depthPx 的比例：线宽决定贴边处的饱和度，模糊半径决定向内的渐隐长度。 */
private const val RING_WIDTH_RATIO: Float = 1.2f
private const val BLUR_RADIUS_RATIO: Float = 0.8f

/**
 * 生成光带遮罩：白色圆角矩形描边（以屏幕边缘为中线，屏外一半被裁掉）加高斯模糊。
 * 返回的位图尺寸是 [size] 的 1/[MASK_DOWNSCALE]，绘制时拉伸到整屏。
 *
 * @param depthPx 光向屏内延伸的大致深度（像素）。
 * @param cornerPx 光带圆角（像素），应贴合屏幕圆角。
 */
internal fun buildGlowMask(
    size: Size,
    depthPx: Float,
    cornerPx: Float,
): ImageBitmap {
    val width = (size.width / MASK_DOWNSCALE).toInt().coerceAtLeast(1)
    val height = (size.height / MASK_DOWNSCALE).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val scale = 1f / MASK_DOWNSCALE
    val blurRadius = (depthPx * BLUR_RADIUS_RATIO * scale).coerceAtLeast(1f)
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = depthPx * RING_WIDTH_RATIO * scale
            color = android.graphics.Color.WHITE
            maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        }
    val corner = cornerPx * scale
    Canvas(bitmap).drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), corner, corner, paint)
    return bitmap.asImageBitmap()
}
