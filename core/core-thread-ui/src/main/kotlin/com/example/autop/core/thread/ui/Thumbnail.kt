/**
 * 对话图片缩略图：输入区的待发送图片与用户气泡里的已发送图片共用。
 * 解码放在 IO 线程并按目标尺寸降采样，避免滚动时在主线程解整张大图。
 */
package com.example.autop.core.thread.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 缩略图：[decode] 接收目标边长（px）返回已降采样的位图；[key] 变化时重新解码。
 * 解码失败（文件被清理）时占位为空，不抛错。
 */
@Composable
public fun Thumbnail(
    key: Any,
    size: Dp,
    contentDescription: String,
    decode: (targetPx: Int) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    val targetPx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key, targetPx) {
        value = withContext(Dispatchers.IO) { decode(targetPx)?.asImageBitmap() }
    }
    val shape = MaterialTheme.shapes.small
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
    }
}

/** 解码文件到不小于 [targetPx] 的最小降采样尺寸。 */
public fun decodeSampledFile(
    file: File,
    targetPx: Int,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds, targetPx) }
    return BitmapFactory.decodeFile(file.path, options)
}

/** 解码内存字节到不小于 [targetPx] 的最小降采样尺寸。 */
public fun decodeSampledBytes(
    bytes: ByteArray,
    targetPx: Int,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds, targetPx) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/** 2 的幂降采样倍数：缩小后短边仍不小于 [targetPx]（Crop 填充时不会糊）。 */
private fun sampleSizeFor(
    bounds: BitmapFactory.Options,
    targetPx: Int,
): Int {
    val shortest = minOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (shortest / (sample * 2) >= targetPx) sample *= 2
    return sample
}
