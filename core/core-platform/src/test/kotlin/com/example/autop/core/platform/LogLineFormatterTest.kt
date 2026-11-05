package com.example.autop.core.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LogLineFormatterTest {
    @Test
    fun `plain values are formatted with key equals value`() {
        val line = LogLineFormatter.format(1000L, "task.start", arrayOf("taskId" to "t-1", "count" to 3), null)
        assertThat(line).isEqualTo("1000 task.start taskId=t-1 count=3")
    }

    @Test
    fun `null values are rendered as dash`() {
        val line = LogLineFormatter.format(1000L, "task.end", arrayOf("taskId" to null), null)
        assertThat(line).isEqualTo("1000 task.end taskId=-")
    }

    @Test
    fun `keys ending with text or content are redacted in every build`() {
        val secret = "窗口里的密码 1234"
        val line =
            LogLineFormatter.format(
                1000L,
                "screen.diff",
                arrayOf("nodeText" to secret, "content" to secret),
                null,
            )
        assertThat(line).doesNotContain(secret)
        assertThat(line).contains("nodeText=")
        assertThat(line).contains("content=")
        assertThat(line).contains("len=${secret.length} h=")
    }

    @Test
    fun `other keys keep raw values for diagnostics`() {
        val line = LogLineFormatter.format(1000L, "llm.call", arrayOf("model" to "gpt-x"), null)
        assertThat(line).contains("model=gpt-x")
    }

    @Test
    fun `error appends exception class message and stack trace`() {
        val line = LogLineFormatter.format(1000L, "task.fail", emptyArray(), IllegalStateException("boom"))
        assertThat(line).startsWith("1000 task.fail error=IllegalStateException: boom\n")
        assertThat(line).contains("java.lang.IllegalStateException: boom")
        assertThat(line).contains("\tat ")
    }

    @Test
    fun `long stack traces are truncated with marker`() {
        val deep =
            IllegalStateException("deep").apply {
                stackTrace = Array(60) { StackTraceElement("Cls", "m$it", "Cls.kt", it) }
            }
        val line = LogLineFormatter.format(1000L, "task.fail", emptyArray(), deep)
        assertThat(line).contains("堆栈截断")
    }
}
