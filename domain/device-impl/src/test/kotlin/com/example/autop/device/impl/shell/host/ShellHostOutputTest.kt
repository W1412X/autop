package com.example.autop.device.impl.shell.host

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShellHostOutputTest {
    private fun bufferWith(
        head: Int,
        tail: Int,
        text: String,
    ): OutputSnapshot {
        val buffer = HeadTailBuffer(headCap = head, tailCap = tail)
        val bytes = text.toByteArray(Charsets.UTF_8)
        buffer.append(bytes, bytes.size)
        return buffer.snapshot()
    }

    @Test
    fun `short output is returned whole and not truncated`() {
        val snap = bufferWith(head = 16, tail = 16, text = "hello")
        assertThat(snap.text).isEqualTo("hello")
        assertThat(snap.truncated).isFalse()
        assertThat(snap.omittedBytes).isEqualTo(0L)
    }

    @Test
    fun `exact fit is not truncated`() {
        val snap = bufferWith(head = 4, tail = 4, text = "12345678")
        assertThat(snap.text).isEqualTo("12345678")
        assertThat(snap.truncated).isFalse()
    }

    @Test
    fun `long output keeps head and tail and reports omitted bytes`() {
        val text = "HEAD" + "x".repeat(92) + "TAIL"
        val snap = bufferWith(head = 4, tail = 4, text = text)
        assertThat(snap.truncated).isTrue()
        assertThat(snap.omittedBytes).isEqualTo(92L)
        assertThat(snap.text).startsWith("HEAD\n")
        assertThat(snap.text).endsWith("\nTAIL")
        assertThat(snap.text).contains("省略 92 字节")
    }

    @Test
    fun `appending in several pieces keeps the same tail`() {
        val buffer = HeadTailBuffer(headCap = 2, tailCap = 3)
        for (piece in listOf("ab", "cdef", "ghijk")) {
            val bytes = piece.toByteArray()
            buffer.append(bytes, bytes.size)
        }
        val snap = buffer.snapshot()
        assertThat(snap.omittedBytes).isEqualTo(6L)
        assertThat(snap.text).startsWith("ab\n")
        assertThat(snap.text).endsWith("ijk")
    }

    @Test
    fun `truncation never splits a multi byte character at the head boundary`() {
        // 每个汉字 3 字节；头部预算 4 字节会切在第二个字的中间，应退回到完整字符处。
        val snap = bufferWith(head = 4, tail = 3, text = "中文字符测试结尾")
        assertThat(snap.text).doesNotContain("\uFFFD")
        assertThat(snap.text).doesNotContain("?")
        assertThat(snap.text).startsWith("中\n")
    }

    @Test
    fun `truncation never leaves a partial character at the tail boundary`() {
        // 尾部 4 字节 = “测”的一个续字节 + “试”；开头的续字节应被丢弃，只剩完整的“试”。
        val snap = bufferWith(head = 2, tail = 4, text = "ab中文字符测试")
        assertThat(snap.text).doesNotContain("?")
        assertThat(snap.text).endsWith("\n试")
    }

    @Test
    fun `invalid utf8 bytes are replaced with a question mark`() {
        val buffer = HeadTailBuffer(headCap = 8, tailCap = 0)
        val bytes = byteArrayOf('o'.code.toByte(), 0xff.toByte(), 'k'.code.toByte())
        buffer.append(bytes, bytes.size)
        assertThat(buffer.snapshot().text).isEqualTo("o?k")
    }
}
