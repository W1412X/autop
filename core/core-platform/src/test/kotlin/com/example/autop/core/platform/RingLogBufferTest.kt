package com.example.autop.core.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RingLogBufferTest {
    @Test
    fun `snapshot keeps oldest first and drops oldest beyond capacity`() {
        val buffer = RingLogBuffer(3)
        listOf("a", "b", "c", "d").forEach { buffer.append(it) }
        assertThat(buffer.snapshot()).containsExactly("b", "c", "d").inOrder()
    }

    @Test
    fun `shrinking capacity trims oldest entries immediately`() {
        val buffer = RingLogBuffer(4)
        listOf("a", "b", "c", "d").forEach { buffer.append(it) }
        buffer.capacity = 2
        assertThat(buffer.capacity).isEqualTo(2)
        assertThat(buffer.snapshot()).containsExactly("c", "d").inOrder()
    }

    @Test
    fun `growing capacity keeps existing entries`() {
        val buffer = RingLogBuffer(2)
        listOf("a", "b").forEach { buffer.append(it) }
        buffer.capacity = 5
        buffer.append("c")
        assertThat(buffer.snapshot()).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `capacity is coerced to at least one`() {
        val buffer = RingLogBuffer(3)
        buffer.capacity = 0
        assertThat(buffer.capacity).isEqualTo(1)
        listOf("a", "b").forEach { buffer.append(it) }
        assertThat(buffer.snapshot()).containsExactly("b")
    }
}
