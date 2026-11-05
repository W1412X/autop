/**
 * 重试策略（docs/tech/03 §4.3）：可重试错误按指数退避 + 抖动，`Retry-After` 优先；
 * 流式请求发出过增量后由调用方以 retryable=false 表达，不再重试。
 */
package com.example.autop.llm.http

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.llm.LlmErrorCodes
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** 重试配置（对应 `llm.retry`）。 */
public data class RetryConfig(
    val maxAttempts: Int,
    val baseDelayMs: Long,
    val maxDelayMs: Long,
    val retryStatus: Set<Int>,
)

/** 一次尝试的结果分类。 */
public sealed interface AttemptOutcome<out T> {
    public data class Success<T>(
        val value: T,
    ) : AttemptOutcome<T>

    public data class Failure(
        val error: AppError,
        val retryable: Boolean,
        val retryAfterMs: Long? = null,
    ) : AttemptOutcome<Nothing>
}

/**
 * 重试执行器：[attempt] 是一次 HTTP 尝试（attemptNumber 从 1 起）；
 * 可重试失败等待后重试，耗尽返回最后一次 [AppError]。
 */
public class RetryRunner(
    private val config: RetryConfig,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val jitter: () -> Double = { defaultJitter() },
) {
    public suspend fun <T> run(attempt: suspend (attemptNumber: Int) -> AttemptOutcome<T>): Outcome<T> {
        var attemptNumber = 1
        while (true) {
            val result = safeAttempt(attempt, attemptNumber)
            if (result is AttemptOutcome.Success) return Outcome.Ok(result.value)
            val failure = result as AttemptOutcome.Failure
            if (!failure.retryable || attemptNumber >= config.maxAttempts) return Outcome.Err(failure.error)
            sleeper(waitMs(failure, attemptNumber))
            attemptNumber += 1
        }
    }

    private suspend fun <T> safeAttempt(
        attempt: suspend (Int) -> AttemptOutcome<T>,
        attemptNumber: Int,
    ): AttemptOutcome<T> =
        try {
            attempt(attemptNumber)
        } catch (e: SocketTimeoutException) {
            AttemptOutcome.Failure(
                error =
                    AppError(
                        code = LlmErrorCodes.TIMEOUT,
                        message = e.message ?: e.toString(),
                        retryable = true,
                        cause = e,
                    ),
                retryable = true,
            )
        } catch (e: IOException) {
            AttemptOutcome.Failure(
                error =
                    AppError(
                        code = LlmErrorCodes.NETWORK,
                        message = e.message ?: e.toString(),
                        retryable = true,
                        cause = e,
                    ),
                retryable = true,
            )
        }

    private fun waitMs(
        failure: AttemptOutcome.Failure,
        attemptNumber: Int,
    ): Long {
        val retryAfter = failure.retryAfterMs
        return if (retryAfter != null) {
            min(retryAfter, config.maxDelayMs)
        } else {
            val exponential = config.baseDelayMs * 2.0.pow(attemptNumber - 1)
            (min(exponential, config.maxDelayMs.toDouble()) * jitter()).toLong()
        }
    }

    private companion object {
        const val JITTER_MIN = 0.8
        const val JITTER_MAX = 1.2

        fun defaultJitter(): Double = JITTER_MIN + Random.nextDouble() * (JITTER_MAX - JITTER_MIN)
    }
}
