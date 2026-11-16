package com.example.autop.device.impl.adb

/**
 * ADB shell v2 数据包（AOSP `shell_protocol`）：1 字节 id + 4 字节小端长度 + 载荷。
 *
 * 流是字节流：一个 A_WRTE 可能只含半个包，也可能含多个包，解码器按流缓冲重组。
 */
internal object AdbV2Frames {
    const val ID_STDIN: Int = 0
    const val ID_STDOUT: Int = 1
    const val ID_STDERR: Int = 2
    const val ID_EXIT: Int = 3
    const val ID_CLOSE_STDIN: Int = 4

    const val HEADER_BYTES: Int = 5

    /** 单包载荷上限，超过视为协议错误（防止异常长度撑爆内存）。 */
    const val MAX_PAYLOAD_BYTES: Int = 16 * 1024 * 1024

    fun encode(
        id: Int,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArray(HEADER_BYTES + payload.size)
        out[0] = id.toByte()
        val len = payload.size
        out[1] = (len and 0xff).toByte()
        out[2] = ((len ushr 8) and 0xff).toByte()
        out[3] = ((len ushr 16) and 0xff).toByte()
        out[4] = ((len ushr 24) and 0xff).toByte()
        payload.copyInto(out, HEADER_BYTES)
        return out
    }
}

/** 增量解码器：每次喂入一段字节，对每个完整包回调一次。非法长度抛 [IllegalStateException]。 */
internal class AdbV2FrameDecoder {
    private var pending: ByteArray = ByteArray(0)

    fun feed(
        chunk: ByteArray,
        onFrame: (id: Int, payload: ByteArray) -> Unit,
    ) {
        pending = if (pending.isEmpty()) chunk.copyOf() else pending + chunk
        var offset = 0
        while (pending.size - offset >= AdbV2Frames.HEADER_BYTES) {
            val id = pending[offset].toInt() and 0xff
            val len = readLittleEndianInt(pending, offset + 1)
            check(len in 0..AdbV2Frames.MAX_PAYLOAD_BYTES) { "shell v2 frame length out of range: $len" }
            val end = offset + AdbV2Frames.HEADER_BYTES + len
            if (end > pending.size) break
            val payload = pending.copyOfRange(offset + AdbV2Frames.HEADER_BYTES, end)
            offset = end
            onFrame(id, payload)
        }
        pending = if (offset == 0) pending else pending.copyOfRange(offset, pending.size)
    }

    private fun readLittleEndianInt(
        bytes: ByteArray,
        at: Int,
    ): Int =
        (bytes[at].toInt() and 0xff) or
            ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or
            ((bytes[at + 3].toInt() and 0xff) shl 24)
}
