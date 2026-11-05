/**
 * 截图坐标换算（docs/design/observation-modes-coordinate-actions §1、§6.3）：
 * 归一化裁剪区/落点 → 原图像素；不再包含编号框生成。
 */
package com.example.autop.device.impl

import android.graphics.Bitmap
import com.example.autop.device.Rect
import com.example.autop.device.core.PixelSize
import android.graphics.Rect as AndroidRect

/**
 * 0–1000 归一化（基准区 [area]，null=全屏）的裁剪区域 → 像素 Rect（裁剪到图内，保证宽高 ≥ 1）。
 * [crop] 为 null 且 [area] 非 null 时默认裁到内容区；两者皆 null 不裁剪。
 */
internal fun pixelCropOf(
    bitmap: Bitmap,
    crop: Rect?,
    area: Rect? = null,
): AndroidRect? {
    if (crop == null && area == null) return null
    val base = area ?: Rect(0, 0, bitmap.width, bitmap.height)
    val left0 = crop?.l ?: 0
    val top0 = crop?.t ?: 0
    val right0 = crop?.r ?: NORMALIZED_LIMIT
    val bottom0 = crop?.b ?: NORMALIZED_LIMIT
    val l = (base.l + left0 / NORMALIZED_MAX * base.width).toInt().coerceIn(0, bitmap.width - 1)
    val t = (base.t + top0 / NORMALIZED_MAX * base.height).toInt().coerceIn(0, bitmap.height - 1)
    val r = (base.l + right0 / NORMALIZED_MAX * base.width).toInt().coerceIn(l + 1, bitmap.width)
    val b = (base.t + bottom0 / NORMALIZED_MAX * base.height).toInt().coerceIn(t + 1, bitmap.height)
    return AndroidRect(l, t, r, b)
}

/** 归一化坐标上限（0–1000 坐标系）。 */
private const val NORMALIZED_MAX = 1000.0
private const val NORMALIZED_LIMIT = 1000

/** 0–1000 归一化落点（基准区 [area]，null=全屏）→ 原图像素准星；坐标缺失返回 null。 */
internal fun markPointOf(
    size: PixelSize,
    x: Int?,
    y: Int?,
    area: Rect? = null,
): MarkPoint? {
    if (x == null || y == null) return null
    val base = area ?: Rect(0, 0, size.width, size.height)
    val px = (base.l + x / NORMALIZED_MAX * base.width).toInt().coerceIn(0, size.width - 1)
    val py = (base.t + y / NORMALIZED_MAX * base.height).toInt().coerceIn(0, size.height - 1)
    return MarkPoint(px, py)
}

/** 落点从原图坐标投影到最终图坐标：裁剪平移 + 缩放（纯数值便于测试）。 */
internal fun projectPointOf(
    point: MarkPoint,
    shiftX: Int,
    shiftY: Int,
    scaleX: Float,
    scaleY: Float,
): MarkPoint =
    MarkPoint(
        x = ((point.x - shiftX) * scaleX).toInt(),
        y = ((point.y - shiftY) * scaleY).toInt(),
    )
