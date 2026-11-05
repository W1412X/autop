package com.example.autop.llm.http

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.llm.LlmErrorCodes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class RetryTest {
    @Test
    fun `first success does not sleep`() =
        runTest {
            val delays = mutableListOf<Long>()
            val runner = runner(delays)

            val result = runner.run { AttemptOutcome.Success("ok") }

            assertThat((result as Outcome.Ok).value).isEqualTo("ok")
            assertThat(delays).isEmpty()
        }

    @Test
    fun `retryable failure retries with exponential backoff`() =
        runTest {
            val delays = mutableListOf<Long>()
            var attempts = 0
            val runner = runner(delays)

            val result =
                runner.run {
                    attempts += 1
                    if (attempts < 3) retryableFailure("boom $attempts") else AttemptOutcome.Success(attempts)
                }

            assertThat((result as Outcome.Ok).value).isEqualTo(3)
            // base 800ms，jitter 固定 1.0：800、1600
            assertThat(delays).containsExactly(800L, 1600L).inOrder()
        }

    @Test
    fun `delays are capped at max delay`() =
        runTest {
            val backoffDelays = mutableListOf<Long>()
            val cappedBackoff = runner(backoffDelays, maxDelayMs = 1000L)
            cappedBackoff.run { retryableFailure("boom") }
            val retryAfterDelays = mutableListOf<Long>()
            val cappedRetryAfter = runner(retryAfterDelays, maxDelayMs = 1000L)
            cappedRetryAfter.run {
                AttemptOutcome.Failure(
                    AppError(code = "test.failure", message = "limited"),
                    retryable = true,
                    retryAfterMs = 999_999L,
                )
            }

            // 第二次退避 1600ms 被 maxDelay 1000 封顶；Retry-After 999_999 同样封顶
            assertThat(backoffDelays).containsExactly(800L, 1000L).inOrder()
            assertThat(retryAfterDelays).containsExactly(1000L, 1000L).inOrder()
        }

    @Test
    fun `retry after takes precedence over exponential backoff`() =
        runTest {
            val delays = mutableListOf<Long>()
            var attempts = 0
            val runner = runner(delays, maxDelayMs = 60_000L)

            val result =
                runner.run {
                    attempts += 1
                    if (attempts == 1) {
                        AttemptOutcome.Failure(
                            AppError(code = "test.failure", message = "limited"),
                            retryable = true,
                            retryAfterMs = 5000L,
                        )
                    } else {
                        AttemptOutcome.Success("ok")
                    }
                }

            assertThat((result as Outcome.Ok).value).isEqualTo("ok")
            assertThat(delays).containsExactly(5000L)
        }

    @Test
    fun `non retryable failure returns error immediately`() =
        runTest {
            val delays = mutableListOf<Long>()
            var attempts = 0
            val runner = runner(delays)

            val result =
                runner.run {
                    attempts += 1
                    AttemptOutcome.Failure(AppError(code = "test.failure", message = "fatal"), retryable = false)
                }

            val err = result as Outcome.Err
            assertThat(err.error.code).isEqualTo("test.failure")
            assertThat(attempts).isEqualTo(1)
            assertThat(delays).isEmpty()
        }

    @Test
    fun `exhausted attempts return last error`() =
        runTest {
            val delays = mutableListOf<Long>()
            var attempts = 0
            val runner = runner(delays)

            val result =
                runner.run {
                    attempts += 1
                    retryableFailure("boom $attempts")
                }

            val err = result as Outcome.Err
            assertThat(err.error.message).isEqualTo("boom 3")
            assertThat(attempts).isEqualTo(3)
            assertThat(delays).hasSize(2)
        }

    @Test
    fun `io exception is retried and reported as network error`() =
        runTest {
            val delays = mutableListOf<Long>()
            var attempts = 0
            val runner = runner(delays)

            val result =
                runner.run<String> {
                    attempts += 1
                    if (attempts < 2) throw IOException("offline") else AttemptOutcome.Success("ok")
                }

            assertThat((result as Outcome.Ok).value).isEqualTo("ok")
            assertThat(delays).hasSize(1)
        }

    @Test
    fun `socket timeout is reported as timeout error`() =
        runTest {
            val delays = mutableListOf<Long>()
            val runner = runner(delays)

            val result =
                runner.run<String> { throw SocketTimeoutException("slow") }

            val err = result as Outcome.Err
            assertThat(err.error.code).isEqualTo(LlmErrorCodes.TIMEOUT)
            assertThat(err.error.retryable).isTrue()
            assertThat(delays).hasSize(2)
        }

    private fun runner(
        delays: MutableList<Long>,
        maxDelayMs: Long = 60_000L,
    ): RetryRunner =
        RetryRunner(
            config =
                RetryConfig(
                    maxAttempts = 3,
                    baseDelayMs = 800L,
                    maxDelayMs = maxDelayMs,
                    retryStatus = emptySet(),
                ),
            sleeper = { delays += it },
            jitter = { 1.0 },
        )

    private fun retryableFailure(message: String): AttemptOutcome.Failure =
        AttemptOutcome.Failure(AppError(code = "test.failure", message = message), retryable = true)
}
