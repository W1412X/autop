package com.example.autop.device.impl.adb

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdbV2FramesTest {
    private fun decodeAll(vararg chunks: ByteArray): List<Pair<Int, String>> {
        val decoder = AdbV2FrameDecoder()
        val out = mutableListOf<Pair<Int, String>>()
        for (chunk in chunks) {
            decoder.feed(chunk) { id, payload -> out += id to String(payload, Charsets.UTF_8) }
        }
        return out
    }

    @Test
    fun `encode writes id and little endian length before payload`() {
        val frame = AdbV2Frames.encode(AdbV2Frames.ID_STDIN, "hi".toByteArray())
        assertThat(frame.toList().map { it.toInt() and 0xff })
            .containsExactly(0, 2, 0, 0, 0, 'h'.code, 'i'.code)
            .inOrder()
    }

    @Test
    fun `decoder returns one frame from one chunk`() {
        val frame = AdbV2Frames.encode(AdbV2Frames.ID_STDOUT, "ok".toByteArray())
        assertThat(decodeAll(frame)).containsExactly(AdbV2Frames.ID_STDOUT to "ok")
    }

    @Test
    fun `decoder reassembles a frame split across chunks`() {
        val frame = AdbV2Frames.encode(AdbV2Frames.ID_STDERR, "split-payload".toByteArray())
        val mid = 7
        assertThat(decodeAll(frame.copyOfRange(0, mid), frame.copyOfRange(mid, frame.size)))
            .containsExactly(AdbV2Frames.ID_STDERR to "split-payload")
    }

    @Test
    fun `decoder splits several frames packed into one chunk`() {
        val packed =
            AdbV2Frames.encode(AdbV2Frames.ID_STDOUT, "a".toByteArray()) +
                AdbV2Frames.encode(AdbV2Frames.ID_STDERR, "bb".toByteArray()) +
                AdbV2Frames.encode(AdbV2Frames.ID_EXIT, byteArrayOf(3))
        assertThat(decodeAll(packed))
            .containsExactly(
                AdbV2Frames.ID_STDOUT to "a",
                AdbV2Frames.ID_STDERR to "bb",
                AdbV2Frames.ID_EXIT to "\u0003",
            ).inOrder()
    }

    @Test
    fun `decoder waits for the header before emitting`() {
        val frame = AdbV2Frames.encode(AdbV2Frames.ID_STDOUT, "x".toByteArray())
        assertThat(decodeAll(frame.copyOfRange(0, 3))).isEmpty()
        assertThat(decodeAll(frame.copyOfRange(0, 3), frame.copyOfRange(3, frame.size)))
            .containsExactly(AdbV2Frames.ID_STDOUT to "x")
    }

    @Test
    fun `decoder rejects an out of range length`() {
        val bad = byteArrayOf(1, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f)
        val failure = runCatching { decodeAll(bad) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }
}
