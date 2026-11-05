package com.example.autop.core.common

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineExceptionHandler
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class CrashHandlingTest {
    private class RecordingLogger : Logger {
        val records = mutableListOf<Record>()

        data class Record(
            val tag: String,
            val event: String,
            val kv: List<Pair<String, Any?>>,
            val error: Throwable?,
        )

        override fun d(
            tag: String,
            event: String,
            vararg kv: Pair<String, Any?>,
        ) = Unit

        override fun i(
            tag: String,
            event: String,
            vararg kv: Pair<String, Any?>,
        ) = Unit

        override fun w(
            tag: String,
            event: String,
            vararg kv: Pair<String, Any?>,
            error: Throwable?,
        ) = Unit

        override fun e(
            tag: String,
            event: String,
            vararg kv: Pair<String, Any?>,
            error: Throwable?,
        ) {
            records += Record(tag, event, kv.toList(), error)
        }
    }

    @Test
    fun `handler records coroutine_crashed with scope name and error`() {
        val logger = RecordingLogger()
        val handler: CoroutineExceptionHandler = logger.exceptionHandler("TestScope")
        val boom = IllegalStateException("boom")
        handler.handleException(EmptyCoroutineContext, boom)
        assertThat(logger.records).hasSize(1)
        val record = logger.records.single()
        assertThat(record.tag).isEqualTo("CoroutineGuard")
        assertThat(record.event).isEqualTo("coroutine_crashed")
        assertThat(record.kv).contains("scope" to "TestScope")
        assertThat(record.error).isSameInstanceAs(boom)
    }
}
