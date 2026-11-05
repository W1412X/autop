/**
 * 内置 8×8 测试图（docs/tech/03 §3 的 vision 探测载荷）：手写 PNG 编码，
 * 不依赖 AWT/Bitmap（本模块是纯 JVM 且要能在 Android 上使用）。
 */
package com.example.autop.llm.http

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater

internal object ProbePng {
    /** 图片边长（像素），同时作为图片块的宽高声明。 */
    const val SIZE: Int = 8

    /** 纯色 RGB PNG 字节。 */
    fun solidRgb8x8(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        out.write(chunk("IHDR", ihdrData()))
        out.write(chunk("IDAT", deflate(rawScanlines())))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun ihdrData(): ByteArray {
        val buffer = ByteBuffer.allocate(IHDR_BYTES)
        buffer.putInt(SIZE)
        buffer.putInt(SIZE)
        buffer.put(BIT_DEPTH)
        buffer.put(COLOR_TYPE_RGB)
        buffer.put(FILTER_NONE)
        buffer.put(FILTER_NONE)
        buffer.put(FILTER_NONE)
        return buffer.array()
    }

    /** 逐行扫描线：每行 = 1 字节过滤器 + SIZE 像素 × 3 字节。 */
    private fun rawScanlines(): ByteArray {
        val raw = ByteArray(SIZE * (FILTER_BYTES + SIZE * BYTES_PER_PIXEL))
        var offset = 0
        repeat(SIZE) {
            raw[offset] = FILTER_NONE
            offset += FILTER_BYTES
            repeat(SIZE) {
                raw[offset] = PIXEL_R
                raw[offset + 1] = PIXEL_G
                raw[offset + 2] = PIXEL_B
                offset += BYTES_PER_PIXEL
            }
        }
        return raw
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFLATE_BUFFER)
            while (!deflater.finished()) {
                val length = deflater.deflate(buffer)
                out.write(buffer, 0, length)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** PNG chunk：长度(4) + 类型(4) + 数据 + CRC32(类型+数据)。 */
    private fun chunk(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val typeBytes = type.encodeToByteArray()
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val out = ByteArrayOutputStream()
        out.write(intBytes(data.size))
        out.write(typeBytes)
        out.write(data)
        out.write(intBytes(crc.value.toInt()))
        return out.toByteArray()
    }

    private fun intBytes(value: Int): ByteArray = ByteBuffer.allocate(INT_BYTES).putInt(value).array()

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private const val BIT_DEPTH: Byte = 8
    private const val COLOR_TYPE_RGB: Byte = 2
    private const val FILTER_NONE: Byte = 0
    private const val FILTER_BYTES = 1
    private const val BYTES_PER_PIXEL = 3
    private const val IHDR_BYTES = 13
    private const val INT_BYTES = 4
    private const val DEFLATE_BUFFER = 512

    // 纯色像素（青柠色调，值均在 Byte 范围内）。
    private const val PIXEL_R: Byte = 0x30
    private const val PIXEL_G: Byte = 0x7A
    private const val PIXEL_B: Byte = 0x4A
}
