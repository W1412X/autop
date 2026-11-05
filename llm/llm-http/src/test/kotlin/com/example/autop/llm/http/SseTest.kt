package com.example.autop.llm.http

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Test

class SseTest {
    @Test
    fun `simple event is emitted on blank line`() =
        runTest {
            val events = source("data: hello\n\n").sseEvents().toList()
            assertThat(events).containsExactly(SseEvent(null, "hello"))
        }

    @Test
    fun `multi line data is joined with newline`() =
        runTest {
            val events = source("data: a\ndata: b\n\n").sseEvents().toList()
            assertThat(events).containsExactly(SseEvent(null, "a\nb"))
        }

    @Test
    fun `comments and unknown fields are ignored`() =
        runTest {
            val events = source(": keepalive\nid: 7\nretry: 100\ndata: x\n\n").sseEvents().toList()
            assertThat(events).containsExactly(SseEvent(null, "x"))
        }

    @Test
    fun `event name is captured and only one leading space is stripped`() =
        runTest {
            val events = source("event: delta\ndata:  y\n\n").sseEvents().toList()
            // 规则 3/4：前导只去一个空格，第二个空格属于值
            assertThat(events).containsExactly(SseEvent("delta", " y"))
        }

    @Test
    fun `crlf line endings are accepted and trailing data without blank line is emitted`() =
        runTest {
            val events = source("data: a\r\n\r\ndata: b").sseEvents().toList()
            assertThat(events).containsExactly(SseEvent(null, "a"), SseEvent(null, "b"))
        }

    @Test
    fun `event split across chunks is parsed`() =
        runTest {
            val events = chunkedSource("da", "ta: he", "llo\n", "\ndata: x\n\n").sseEvents().toList()
            assertThat(events).containsExactly(SseEvent(null, "hello"), SseEvent(null, "x"))
        }

    private fun source(text: String): BufferedSource = Buffer().writeUtf8(text)

    private fun chunkedSource(vararg chunks: String): BufferedSource =
        ChunkedSource(chunks.map { it.toByteArray() }.toMutableList()).buffer()
}

/** 每次 read 只投喂一个 chunk 的 Source（模拟网络分段到达）。 */
private class ChunkedSource(
    private val chunks: MutableList<ByteArray>,
) : Source {
    private var index = 0

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (index >= chunks.size) return -1L
        val chunk = chunks[index++]
        sink.write(chunk)
        return chunk.size.toLong()
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() = Unit
}
