/**
 * [ImageLoader] 的 Android 实现（docs/tech/03 §1）：Bitmap 解码、长边限幅到 maxEdge 后重编码。
 * [fileRef] 是 `ThreadFiles` 的**相对 filesDir** 路径（也兼容绝对路径）——此前直接拿相对
 * 路径 `decodeFile`，Android 进程 cwd 不是 filesDir，所有图片必读失败（N178）。
 * 读取失败返回 null 并 `Log.w` 留痕（调用方降级为占位文本，不再炸整个请求）。
 */
package com.example.autop.core.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.llm.ImageLoader
import com.example.autop.llm.LoadedImage
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

public class BitmapImageLoader(
    private val dispatchers: DispatcherProvider,
    private val filesDir: File,
) : ImageLoader {
    override suspend fun load(
        fileRef: String,
        mime: String,
        maxEdge: Int,
    ): LoadedImage? =
        withContext(dispatchers.io) {
            val file = imageFileOf(filesDir, fileRef)
            val sampled = decodeScaled(file, maxEdge)
            val encoded = sampled?.let { encode(it, mime) }
            sampled?.recycle()
            if (encoded == null) {
                Log.w(TAG, "image_load_failed: ref=$fileRef resolved=${file.path}")
            }
            encoded
        }

    private fun decodeScaled(
        file: File,
        maxEdge: Int,
    ): Bitmap? {
        val path = file.path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val usable = bounds.outWidth > 0 && bounds.outHeight > 0
        val decoded = if (usable) BitmapFactory.decodeFile(path, sampleOptions(bounds, maxEdge)) else null
        return decoded?.let { scaleToMaxEdge(it, maxEdge) }
    }

    private fun sampleOptions(
        bounds: BitmapFactory.Options,
        maxEdge: Int,
    ): BitmapFactory.Options {
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > maxEdge * SAMPLE_MARGIN) {
            sample *= SAMPLE_STEP
        }
        return BitmapFactory.Options().apply { inSampleSize = sample }
    }

    /** 长边超过 [maxEdge] 时等比缩小；源图在缩放后立即回收。 */
    private fun scaleToMaxEdge(
        bitmap: Bitmap,
        maxEdge: Int,
    ): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        bitmap.recycle()
        return scaled
    }

    /** 重编码：png 保真，其余（含 webp）转 jpeg（不读 SDK_INT，见 questions.md N20）。 */
    private fun encode(
        bitmap: Bitmap,
        mime: String,
    ): LoadedImage? {
        val outMime = if (mime == MIME_PNG) MIME_PNG else MIME_JPEG
        val format = if (outMime == MIME_PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val stream = ByteArrayOutputStream()
        val ok = bitmap.compress(format, COMPRESS_QUALITY, stream)
        return if (ok) LoadedImage(stream.toByteArray(), outMime) else null
    }

    private companion object {
        const val TAG = "BitmapImageLoader"
        const val MIME_PNG = "image/png"
        const val MIME_JPEG = "image/jpeg"
        const val COMPRESS_QUALITY = 90
        const val SAMPLE_STEP = 2
        const val SAMPLE_MARGIN = 2
    }
}

/** fileRef → 文件：绝对路径直接用，相对路径解析到 [filesDir] 下（ThreadFiles 的 ref 形态，N178）。 */
internal fun imageFileOf(
    filesDir: File,
    fileRef: String,
): File {
    val direct = File(fileRef)
    return if (direct.isAbsolute) direct else File(filesDir, fileRef)
}
