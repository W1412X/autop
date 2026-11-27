package com.example.autop.device.impl.bgdisplay.helper

import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.util.Log

/**
 * 与 DSH-go 相同的按次捕获缓冲：每次截图换用新的 ImageReader/Surface，避免长期复用消费端。
 * 创建、换 Surface、编码和释放都在 DisplaySession 的同一临界区内串行执行。
 */
internal class BackgroundFrames(
    private val display: VirtualDisplay,
    private val width: Int,
    private val height: Int,
    initialReader: ImageReader,
) : AutoCloseable {
    private val lock = Any()
    private var reader: ImageReader = initialReader
    private var pendingReader: ImageReader? = null
    private var closed = false

    val surface: android.view.Surface
        get() =
            synchronized(lock) {
                check(!closed) { "background frames closed" }
                reader.surface
            }

    @Suppress("TooGenericExceptionCaught") // Surface 切换失败时必须关闭待交接 reader，避免 native 资源泄漏。
    fun rotateForCapture(): ImageReader =
        synchronized(lock) {
            check(!closed && pendingReader == null) { "background capture allocation pending" }
            val next =
                ImageReader.newInstance(
                    width,
                    height,
                    android.graphics.PixelFormat.RGBA_8888,
                    IMAGE_READER_MAX_IMAGES,
                )
            pendingReader = next
            try {
                display.surface = next.surface
                val previous = reader
                reader = next
                pendingReader = null
                runCatching { previous.close() }.onFailure { error ->
                    Log.w("BackgroundDisplayHost", "previous frame reader close failed", error)
                }
                next
            } catch (error: Throwable) {
                closePendingReader(next)
                throw error
            }
        }

    private fun closePendingReader(next: ImageReader) {
        pendingReader = null
        runCatching { next.close() }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { pendingReader?.close() }
            pendingReader = null
            runCatching { reader.close() }
        }
    }

    private companion object {
        const val IMAGE_READER_MAX_IMAGES = 2
    }
}
