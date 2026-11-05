package com.example.autop.core.common

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class OutcomeTest {
    @Test
    fun `map transforms ok value and passes error through`() {
        val ok = Outcome.Ok(2).map { it * 10 }
        assertThat((ok as Outcome.Ok).value).isEqualTo(20)
        val err: Outcome<Int> = Outcome.Err(AppError(CommonErrorCodes.IO, "boom"))
        assertThat(err.map { it * 10 }).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun `flatMap chains outcomes`() {
        fun parse(s: String): Outcome<Int> =
            s
                .toIntOrNull()
                ?.let { Outcome.Ok(it) }
                ?: Outcome.Err(AppError(CommonErrorCodes.INVALID_ARGUMENT, "not a number"))
        val ok = Outcome.Ok("42").flatMap { parse(it) }
        assertThat((ok as Outcome.Ok).value).isEqualTo(42)
        val err = Outcome.Ok("x").flatMap { parse(it) }
        assertThat((err as Outcome.Err).error.code).isEqualTo(CommonErrorCodes.INVALID_ARGUMENT)
    }

    @Test
    fun `onErr observes error once without changing outcome`() {
        var seen: AppError? = null
        val err = Outcome.Err(AppError(CommonErrorCodes.TIMEOUT, "slow"))
        val returned = err.onErr { seen = it }
        assertThat(seen?.code).isEqualTo(CommonErrorCodes.TIMEOUT)
        assertThat(returned).isSameInstanceAs(err)
    }

    @Test
    fun `getOrNull returns value or null`() {
        assertThat(Outcome.Ok("v").getOrNull()).isEqualTo("v")
        val err: Outcome<String> = Outcome.Err(AppError(CommonErrorCodes.UNKNOWN, "x"))
        assertThat(err.getOrNull()).isNull()
    }

    @Test
    fun `runCatchingCancellable wraps success and exception`() =
        runTest {
            val ok = runCatchingCancellable(CommonErrorCodes.IO) { "done" }
            assertThat((ok as Outcome.Ok).value).isEqualTo("done")
            val err = runCatchingCancellable(CommonErrorCodes.IO) { error("boom") }
            assertThat((err as Outcome.Err).error.code).isEqualTo(CommonErrorCodes.IO)
            assertThat(err.error.message).contains("boom")
        }

    @Test
    fun `runCatchingCancellable does not swallow cancellation`() =
        runTest {
            val thrown =
                runCatching {
                    runCatchingCancellable(CommonErrorCodes.IO) { throw CancellationException("stop") }
                }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(CancellationException::class.java)
        }

    @Test
    fun `runCatchingCancellable body may call suspend functions`() =
        runTest {
            val addends = listOf(3, 4)

            suspend fun step(): Int = addends.sum()
            val ok = runCatchingCancellable(CommonErrorCodes.IO) { step() }
            assertThat((ok as Outcome.Ok).value).isEqualTo(7)
        }

    @Test
    fun `catchingAsError converts exception to error`() {
        val err = catchingAsError(CommonErrorCodes.SERIALIZATION) { error("bad json") }
        assertThat((err as Outcome.Err).error.code).isEqualTo(CommonErrorCodes.SERIALIZATION)
    }
}
