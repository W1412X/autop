/**
 * 通知大图标静态表情（P1-15）：品牌色实心盘 + 白色眼型，按 [NotifyFace] 取代表帧。
 * 不依赖 core-ui（domain 层不能依赖 feature 组件），几何按表情渲染画布等比简化。
 */
@file:Suppress("MagicNumber")

package com.example.autop.task.impl.notify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** 把 [NotifyFace] 画成通知大图标位图（192px，系统按需缩放）。 */
internal object NotifyFacePainter {
    /** 渲染一次给定表情。 */
    fun render(face: NotifyFace): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = BODY_COLOR
        canvas.drawCircle(CX, CY, DISC_RADIUS, paint)
        paint.color = Color.WHITE
        drawEyes(canvas, paint, face)
        drawSatellite(canvas, paint, face)
        return bitmap
    }

    private fun drawEyes(
        canvas: Canvas,
        paint: Paint,
        face: NotifyFace,
    ) {
        paint.style = Paint.Style.FILL
        when (face) {
            NotifyFace.IDLE, NotifyFace.BLOCKED -> drawCapsules(canvas, paint, EYE_HALF_W, EYE_HALF_H)
            NotifyFace.WAITING -> drawCapsules(canvas, paint, EYE_HALF_W + 2f, EYE_HALF_H + 3f)
            NotifyFace.SUCCESS -> drawCurves(canvas, paint, controlY = -EYE_CURVE)
            NotifyFace.FAILURE -> drawCurves(canvas, paint, controlY = EYE_CURVE)
            NotifyFace.LOST -> drawCrosses(canvas, paint)
        }
        if (face == NotifyFace.BLOCKED) drawBrows(canvas, paint)
    }

    private fun drawCapsules(
        canvas: Canvas,
        paint: Paint,
        halfW: Float,
        halfH: Float,
    ) {
        EYE_CENTERS.forEach { (cx, cy) ->
            canvas.drawRoundRect(RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH), halfW, halfW, paint)
        }
    }

    private fun drawCurves(
        canvas: Canvas,
        paint: Paint,
        controlY: Float,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = STROKE
        paint.strokeCap = Paint.Cap.ROUND
        EYE_CENTERS.forEach { (cx, cy) ->
            val path =
                Path().apply {
                    moveTo(cx - EYE_HALF_W, cy)
                    quadTo(cx, cy + controlY, cx + EYE_HALF_W, cy)
                }
            canvas.drawPath(path, paint)
        }
    }

    private fun drawCrosses(
        canvas: Canvas,
        paint: Paint,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = STROKE
        paint.strokeCap = Paint.Cap.ROUND
        EYE_CENTERS.forEach { (cx, cy) ->
            canvas.drawLine(cx - CROSS_HALF, cy - CROSS_HALF, cx + CROSS_HALF, cy + CROSS_HALF, paint)
            canvas.drawLine(cx + CROSS_HALF, cy - CROSS_HALF, cx - CROSS_HALF, cy + CROSS_HALF, paint)
        }
    }

    private fun drawBrows(
        canvas: Canvas,
        paint: Paint,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val (left, right) = EYE_CENTERS
        canvas.drawLine(
            left.first - BROW_W,
            left.second - BROW_RISE,
            left.first + BROW_W,
            left.second + BROW_RISE,
            paint,
        )
        canvas.drawLine(
            right.first - BROW_W,
            right.second + BROW_RISE,
            right.first + BROW_W,
            right.second - BROW_RISE,
            paint,
        )
    }

    private fun drawSatellite(
        canvas: Canvas,
        paint: Paint,
        face: NotifyFace,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        val radius = if (face == NotifyFace.WAITING) SATELLITE_RADIUS + 2f else SATELLITE_RADIUS
        canvas.drawCircle(CX + SATELLITE_OFFSET, CY - SATELLITE_OFFSET, radius, paint)
    }

    private const val SIZE = 192
    private const val CX = 96f
    private const val CY = 96f
    private const val DISC_RADIUS = 66f

    /** 品牌主色（light primary #4C662B），与启动图标同源。 */
    private val BODY_COLOR = Color.rgb(0x4C, 0x66, 0x2B)

    private const val EYE_HALF_W = 7f
    private const val EYE_HALF_H = 13.5f
    private const val EYE_CURVE = 11f
    private const val STROKE = 7f
    private const val CROSS_HALF = 9f
    private const val BROW_W = 9f
    private const val BROW_RISE = 5f
    private const val SATELLITE_OFFSET = 56f
    private const val SATELLITE_RADIUS = 11f

    private val EYE_CENTERS =
        listOf(
            CX - 19.5f to CY + 8f,
            CX + 19.5f to CY + 8f,
        )
}
