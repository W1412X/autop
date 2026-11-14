package com.example.autop.llm.http

import com.example.autop.llm.LlmErrorCodes
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HttpErrorMapperTest {
    @Test
    fun `400 with context pattern maps to context exceeded`() {
        val error =
            mapHttpError(400, "Error: prompt is too long", listOf("prompt is too long"), CONTEXT_STATUS, RETRY_STATUS)

        assertThat(error.code).isEqualTo(LlmErrorCodes.CONTEXT_EXCEEDED)
        assertThat(error.retryable).isFalse()
    }

    @Test
    fun `400 without context pattern maps to bad request`() {
        val error = mapHttpError(400, "bad request", listOf("prompt is too long"), CONTEXT_STATUS, RETRY_STATUS)

        assertThat(error.code).isEqualTo(LlmErrorCodes.BAD_REQUEST)
    }

    @Test
    fun `402 maps to quota exhausted and is not retryable`() {
        val error = mapHttpError(402, "payment required", emptyList(), CONTEXT_STATUS, RETRY_STATUS)
        assertThat(error.code).isEqualTo(LlmErrorCodes.QUOTA_EXHAUSTED)
        assertThat(error.retryable).isFalse()
    }

    @Test
    fun `429 with insufficient_quota maps to quota exhausted and is not retryable`() {
        val error =
            mapHttpError(
                429,
                "{\"error\":{\"code\":\"insufficient_quota\"}}",
                emptyList(),
                CONTEXT_STATUS,
                RETRY_STATUS,
            )
        assertThat(error.code).isEqualTo(LlmErrorCodes.QUOTA_EXHAUSTED)
        assertThat(error.retryable).isFalse()
    }

    @Test
    fun `plain 429 stays rate limited and retryable`() {
        val error = mapHttpError(429, "slow down", emptyList(), CONTEXT_STATUS, RETRY_STATUS)
        assertThat(error.code).isEqualTo(LlmErrorCodes.RATE_LIMITED)
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `400 with credit balance message maps to quota exhausted`() {
        val error =
            mapHttpError(400, "Your credit balance is too low", emptyList(), CONTEXT_STATUS, RETRY_STATUS)
        assertThat(error.code).isEqualTo(LlmErrorCodes.QUOTA_EXHAUSTED)
    }

    @Test
    fun `401 and 403 map to auth`() {
        val unauthorized = mapHttpError(401, "no", emptyList(), CONTEXT_STATUS, RETRY_STATUS)
        val forbidden = mapHttpError(403, "no", emptyList(), CONTEXT_STATUS, RETRY_STATUS)
        assertThat(unauthorized.code).isEqualTo(LlmErrorCodes.AUTH)
        assertThat(forbidden.code).isEqualTo(LlmErrorCodes.AUTH)
    }

    @Test
    fun `429 maps to rate limited and is retryable`() {
        val error = mapHttpError(429, "slow down", emptyList(), CONTEXT_STATUS, RETRY_STATUS)

        assertThat(error.code).isEqualTo(LlmErrorCodes.RATE_LIMITED)
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `5xx maps to server and is retryable`() {
        val error = mapHttpError(503, "unavailable", emptyList(), CONTEXT_STATUS, RETRY_STATUS)

        assertThat(error.code).isEqualTo(LlmErrorCodes.SERVER)
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `other 4xx map to bad request and unexpected status maps to bad response`() {
        val notFound = mapHttpError(404, "missing", emptyList(), CONTEXT_STATUS, RETRY_STATUS)
        assertThat(notFound.code).isEqualTo(LlmErrorCodes.BAD_REQUEST)
        assertThat(notFound.retryable).isFalse()

        val weird = mapHttpError(600, "weird", emptyList(), CONTEXT_STATUS, RETRY_STATUS)
        assertThat(weird.code).isEqualTo(LlmErrorCodes.BAD_RESPONSE)
    }

    @Test
    fun `408 is retryable`() {
        val error = mapHttpError(408, "timeout", emptyList(), CONTEXT_STATUS, setOf(408))

        assertThat(error.code).isEqualTo(LlmErrorCodes.BAD_REQUEST)
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `413 with pattern is context exceeded`() {
        val error = mapHttpError(413, "prompt is too long", listOf("too long"), CONTEXT_STATUS, RETRY_STATUS)

        assertThat(error.code).isEqualTo(LlmErrorCodes.CONTEXT_EXCEEDED)
    }

    @Test
    fun `context exceeded is never retryable`() {
        val error = mapHttpError(400, "prompt is too long", listOf("prompt is too long"), CONTEXT_STATUS, setOf(400))

        assertThat(error.code).isEqualTo(LlmErrorCodes.CONTEXT_EXCEEDED)
        assertThat(error.retryable).isFalse()
    }

    @Test
    fun `context matching is case insensitive and ignores blank patterns`() {
        assertThat(matchesContextExceeded("Context Length Exceeded", listOf("context length exceeded"))).isTrue()
        assertThat(matchesContextExceeded("anything", listOf("  ", ""))).isFalse()
    }

    @Test
    fun `retry after seconds are converted to millis`() {
        assertThat(parseRetryAfterMs("3", nowMs = 0L)).isEqualTo(3000L)
        assertThat(parseRetryAfterMs("-1", nowMs = 0L)).isEqualTo(0L)
    }

    @Test
    fun `retry after http date is clamped to zero in the past`() {
        val date = "Wed, 21 Oct 2015 07:28:00 GMT"
        val dateMs = 1_445_412_480_000L

        assertThat(parseRetryAfterMs(date, nowMs = dateMs - 2500L)).isEqualTo(2500L)
        assertThat(parseRetryAfterMs(date, nowMs = dateMs + 10_000L)).isEqualTo(0L)
    }

    @Test
    fun `retry after invalid values return null`() {
        assertThat(parseRetryAfterMs(null, nowMs = 0L)).isNull()
        assertThat(parseRetryAfterMs("", nowMs = 0L)).isNull()
        assertThat(parseRetryAfterMs("soon", nowMs = 0L)).isNull()
    }

    private companion object {
        val CONTEXT_STATUS = setOf(400, 413, 422)
        val RETRY_STATUS = setOf(408, 409, 429, 500, 502, 503, 504, 529)
    }
}
