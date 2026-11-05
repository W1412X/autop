package com.example.autop.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LogRedactTest {
    @Test
    fun `redacted text keeps only length and hash`() {
        val redacted = LogRedact.text("屏幕上的敏感内容")
        assertThat(redacted).startsWith("len=8 h=")
        assertThat(redacted).doesNotContain("敏感")
    }

    @Test
    fun `same input produces same fingerprint`() {
        assertThat(LogRedact.text("abc")).isEqualTo(LogRedact.text("abc"))
    }

    @Test
    fun `different input produces different fingerprint`() {
        assertThat(LogRedact.text("abc")).isNotEqualTo(LogRedact.text("abd"))
    }

    @Test
    fun `null and empty are marked without hash`() {
        assertThat(LogRedact.text(null)).isEqualTo("len=0 h=-")
        assertThat(LogRedact.text("")).isEqualTo("len=0 h=-")
    }
}
