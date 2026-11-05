/**
 * 截图处理（docs/design/observation-modes-coordinate-actions §6.3）：
 * 裁剪 → 长边缩放 → 落点准星（可选）→ JPEG 编码。不再绘制编号框与坐标网格。
 */
package com.example.autop.device.impl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.core.PixelSize
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import android.graphics.Rect as AndroidRect

/** 处理完成的截图。 */
public data class ProcessedShot(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
)

/** 截图上的一个落点准星标注（像素坐标，原始截图坐标系）。 */
public data class MarkPoint(
    val x: Int,
    val y: Int,
)

/** 截图裁剪、缩放与落点标注。 */
@Singleton
public class ScreenshotProcessor
    @Inject
    constructor(
        private val configProvider: ConfigProvider,
    ) {
        /** 裁剪（[crop] 为原图坐标）→ 长边 ≤ `maxEdge` → 落点准星 → JPEG。 */
        public fun process(
            source: Bitmap,
            crop: AndroidRect? = null,
            points: List<MarkPoint> = emptyList(),
        ): ProcessedShot {
            val cfg = configProvider.current()[DeviceConfig.SCREENSHOT]
            val cropped =
                crop?.let {
                    Bitmap.createBitmap(source, it.left, it.top, it.width(), it.height())
                }
            val base = cropped ?: source
            val scaled = scale(base, fitWithin(base, cfg.maxEdge))
            try {
                val sx = scaled.width.toFloat() / base.width
                val sy = scaled.height.toFloat() / base.height
                val projected =
                    points.map {
                        projectPointOf(it, crop?.left ?: 0, crop?.top ?: 0, sx, sy)
                    }
                val result = if (projected.isEmpty()) scaled else markPoints(scaled, projected)
                try {
                    val bytes = encode(result, cfg.jpegQuality)
                    return ProcessedShot(bytes, result.width, result.height)
                } finally {
                    if (result !== scaled) result.recycle()
                }
            } finally {
                if (scaled !== base) scaled.recycle()
                cropped?.recycle()
            }
        }

        private fun fitWithin(
            base: Bitmap,
            maxEdge: Int,
        ): PixelSize {
            val longEdge = maxOf(base.width, base.height)
            if (longEdge <= maxEdge) return PixelSize(base.width, base.height)
            val ratio = maxEdge.toDouble() / longEdge
            return PixelSize(
                (base.width * ratio).toInt().coerceAtLeast(1),
                (base.height * ratio).toInt().coerceAtLeast(1),
            )
        }

        private fun scale(
            source: Bitmap,
            target: PixelSize,
        ): Bitmap =
            if (target.width == source.width && target.height == source.height) {
                source
            } else {
                Bitmap.createScaledBitmap(source, target.width, target.height, true)
            }

        private fun encode(
            bitmap: Bitmap,
            quality: Int,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return out.toByteArray()
        }
    }

/** 落点准星叠加：十字线＋中心空心圆；改写一份副本不动原图。 */
private fun markPoints(
    source: Bitmap,
    points: List<MarkPoint>,
): Bitmap {
    val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(mutable)
    val paint =
        Paint().apply {
            color = POINT_COLOR
            style = Paint.Style.STROKE
            strokeWidth = POINT_STROKE_PX.toFloat()
            isAntiAlias = true
        }
    for (point in points) {
        val x = point.x.toFloat()
        val y = point.y.toFloat()
        canvas.drawLine(x - POINT_ARM_PX, y, x + POINT_ARM_PX, y, paint)
        canvas.drawLine(x, y - POINT_ARM_PX, x, y + POINT_ARM_PX, paint)
        canvas.drawCircle(x, y, POINT_DOT_PX, paint)
    }
    return mutable
}

/** 落点准星样式（醒目红，实现细节）。 */
private val POINT_COLOR: Int = Color.rgb(255, 64, 64)
private const val POINT_STROKE_PX = 5
private const val POINT_ARM_PX = 26f
private const val POINT_DOT_PX = 7f
