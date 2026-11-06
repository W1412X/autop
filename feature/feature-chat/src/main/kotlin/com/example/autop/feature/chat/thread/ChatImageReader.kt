/**
 * 相册图片读取：把 Uri 解码、缩放到长边上限、压成 JPEG。
 * 用 [ImageDecoder] 解码，它按 EXIF 自动转正；缩放与压缩参数来自 `ui.chat.attach*` 配置。
 */
package com.example.autop.feature.chat.thread

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.runCatchingCancellable
import com.example.autop.core.config.UiChatSection
import com.example.autop.task.ImageAttachment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlin.math.roundToInt

/** 把选中的图片转成可发送的 [ImageAttachment]。 */
public class ChatImageReader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
    ) {
        /** 读取失败（格式不支持、文件不可读、解码出错）返回 [Outcome.Err]。 */
        internal suspend fun read(
            uri: Uri,
            config: UiChatSection,
        ): Outcome<ImageAttachment> =
            withContext(dispatchers.io) {
                runCatchingCancellable(CommonErrorCodes.IO) {
                    val bitmap = decode(uri, config.attachMaxEdge)
                    try {
                        encode(bitmap, config.attachJpegQuality)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }

        private fun decode(
            uri: Uri,
            maxEdge: Int,
        ): Bitmap {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > maxEdge) {
                    val scale = maxEdge.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }

        private fun encode(
            bitmap: Bitmap,
            quality: Int,
        ): ImageAttachment {
            val stream = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                throw IOException("jpeg compress failed")
            }
            return ImageAttachment(
                bytes = stream.toByteArray(),
                mime = MIME_JPEG,
                width = bitmap.width,
                height = bitmap.height,
            )
        }

        private companion object {
            const val MIME_JPEG = "image/jpeg"
        }
    }
