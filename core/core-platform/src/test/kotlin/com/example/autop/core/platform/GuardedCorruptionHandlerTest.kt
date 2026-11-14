package com.example.autop.core.platform

import androidx.datastore.core.CorruptionException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** 损坏处理守卫（ADR-0032）：拦截期间抛出异常（文件不被替换），就绪后返回默认值。 */
class GuardedCorruptionHandlerTest {
    @Test
    fun `blocked state rethrows and ready state falls back to the default`() =
        runBlocking {
            val handler = guardedReplaceHandler { "default" }
            val ex = CorruptionException("broken", null)

            DataSchemaState.block()
            val blocked = runCatching { handler.handleCorruption(ex) }
            assertThat(blocked.isFailure).isTrue()
            assertThat(blocked.exceptionOrNull()).isSameInstanceAs(ex)

            DataSchemaState.markReady()
            assertThat(handler.handleCorruption(ex)).isEqualTo("default")
        }
}
