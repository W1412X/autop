package com.example.autop.device.impl.shell.host

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 一个输出流截断后的快照。 */
internal data class OutputSnapshot(
    val text: String,
    val truncated: Boolean,
    val omittedBytes: Long,
)

/**
 * 单个输出流的头尾保留缓冲（shell-exec 设计 §6.1）。
 *
 * 头部保留前 [headCap] 字节，尾部保留最后 [tailCap] 字节，中间只计数。
 * 截断点不切开 UTF-8 多字节字符；非法字节替换为 `?`。
 * 所有方法同步，因为读线程写入、等待线程读取快照。
 */
internal class HeadTailBuffer(
    private val headCap: Int,
    private val tailCap: Int,
) {
    private val head = ByteArrayOutputStream(headCap.coerceAtLeast(0))
    private val tail = ByteArray(tailCap.coerceAtLeast(0))
    private var tailNext = 0
    private var tailLen = 0
    private var totalBytes = 0L

    @Synchronized
    fun append(
        bytes: ByteArray,
        length: Int,
    ) {
        totalBytes += length
        val headRoom = headCap - head.size()
        var from = 0
        if (headRoom > 0) {
            val n = minOf(headRoom, length)
            head.write(bytes, 0, n)
            from = n
        }
        if (tail.isEmpty()) return
        for (i in from until length) {
            tail[tailNext] = bytes[i]
            tailNext = (tailNext + 1) % tail.size
            if (tailLen < tail.size) tailLen++
        }
    }

    @Synchronized
    fun snapshot(): OutputSnapshot {
        val headBytes = head.toByteArray()
        val tailBytes = orderedTail()
        val kept = headBytes.size.toLong() + tailBytes.size
        val omitted = totalBytes - kept
        if (omitted <= 0L) {
            return OutputSnapshot(decode(headBytes + tailBytes), truncated = false, omittedBytes = 0L)
        }
        val headPart = decode(trimIncompleteEnd(headBytes))
        val tailPart = decode(skipLeadingContinuation(tailBytes))
        val text = "$headPart\n…[省略 $omitted 字节]…\n$tailPart"
        return OutputSnapshot(text, truncated = true, omittedBytes = omitted)
    }

    private fun orderedTail(): ByteArray {
        if (tailLen == 0) return ByteArray(0)
        val out = ByteArray(tailLen)
        val start = (tailNext - tailLen + tail.size) % tail.size
        for (i in 0 until tailLen) {
            out[i] = tail[(start + i) % tail.size]
        }
        return out
    }

    internal companion object {
        /** 把字节按 UTF-8 解码，非法字节替换为 `?`。 */
        fun decode(bytes: ByteArray): String =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith("?")
                .decode(ByteBuffer.wrap(bytes))
                .toString()

        /** 去掉末尾被截断的不完整字符（最多回看 3 字节）。 */
        fun trimIncompleteEnd(bytes: ByteArray): ByteArray {
            var lead = bytes.size - 1
            var back = 0
            while (lead >= 0 && back < MAX_CHAR_BYTES - 1 && isContinuation(bytes[lead])) {
                lead--
                back++
            }
            if (lead < 0) return bytes
            val expected = sequenceLength(bytes[lead])
            return if (bytes.size - lead < expected) bytes.copyOf(lead) else bytes
        }

        /** 去掉开头不完整的字符续字节（最多 3 字节）。 */
        fun skipLeadingContinuation(bytes: ByteArray): ByteArray {
            var start = 0
            while (start < bytes.size && start < MAX_CHAR_BYTES - 1 && isContinuation(bytes[start])) {
                start++
            }
            return if (start == 0) bytes else bytes.copyOfRange(start, bytes.size)
        }

        private fun isContinuation(b: Byte): Boolean = (b.toInt() and UTF8_CONT_MASK) == UTF8_CONT_TAG

        /** 按首字节的高位判断 UTF-8 字符长度；非法首字节按 1 字节处理。 */
        private fun sequenceLength(lead: Byte): Int {
            val v = lead.toInt() and BYTE_MASK
            return when {
                v < UTF8_CONT_TAG -> 1
                (v and UTF8_LEN2_MASK) == UTF8_LEN2_TAG -> UTF8_LEN_2
                (v and UTF8_LEN3_MASK) == UTF8_LEN3_TAG -> UTF8_LEN_3
                (v and UTF8_LEN4_MASK) == UTF8_LEN4_TAG -> UTF8_LEN_4
                else -> 1
            }
        }

        private const val MAX_CHAR_BYTES = 4
        private const val UTF8_LEN_2 = 2
        private const val UTF8_LEN_3 = 3
        private const val UTF8_LEN_4 = 4
        private const val BYTE_MASK = 0xFF
        private const val UTF8_CONT_MASK = 0xC0
        private const val UTF8_CONT_TAG = 0x80
        private const val UTF8_LEN2_MASK = 0xE0
        private const val UTF8_LEN2_TAG = 0xC0
        private const val UTF8_LEN3_MASK = 0xF0
        private const val UTF8_LEN3_TAG = 0xE0
        private const val UTF8_LEN4_MASK = 0xF8
        private const val UTF8_LEN4_TAG = 0xF0
    }
}
