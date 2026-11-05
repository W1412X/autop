package com.example.autop.core.platform

import com.example.autop.core.common.Clock
import com.example.autop.core.common.LogFiles
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AndroidLoggerTest {
    private class FakeClock : Clock {
        override fun nowMillis(): Long = 42L
    }

    private class FakeLogFiles : LogFiles {
        val appended = mutableListOf<String>()

        override val directoryPath: String = "/fake/logs"

        override fun append(line: String) {
            appended += line
        }

        override fun totalBytes(): Long = 0L

        override fun readAll(): String = appended.joinToString("\n")

        override fun clear(): Long = 0L
    }

    @Test
    fun `release build drops debug level unless verboseLogging enabled`() {
        val logger = AndroidLogger(FakeClock(), isDebug = false)
        logger.d("T", "evt.drop")
        assertThat(logger.snapshot()).isEmpty()
        logger.verboseLogging = true
        logger.d("T", "evt.keep")
        assertThat(logger.snapshot()).containsExactly("42 evt.keep")
    }

    @Test
    fun `debug build always writes debug level`() {
        val logger = AndroidLogger(FakeClock(), isDebug = true)
        logger.d("T", "evt")
        assertThat(logger.snapshot()).containsExactly("42 evt")
    }

    @Test
    fun `every formatted line is appended to log files`() {
        val files = FakeLogFiles()
        val logger = AndroidLogger(FakeClock(), isDebug = false, logFiles = files)
        logger.i("T", "i1")
        logger.e("T", "e1", error = IllegalStateException("x"))
        assertThat(files.appended).hasSize(2)
        assertThat(files.appended[0]).contains("42 i1")
        assertThat(files.appended[1]).contains("42 e1")
        assertThat(files.appended[1]).contains("java.lang.IllegalStateException: x")
    }

    @Test
    fun `bufferCapacity shrinks buffer and keeps newest lines`() {
        val logger = AndroidLogger(FakeClock(), isDebug = true, initialBufferCapacity = 4)
        repeat(6) { logger.i("T", "e$it") }
        assertThat(logger.snapshot()).hasSize(4)
        logger.bufferCapacity = 2
        assertThat(logger.snapshot()).containsExactly("42 e4", "42 e5")
    }
}
