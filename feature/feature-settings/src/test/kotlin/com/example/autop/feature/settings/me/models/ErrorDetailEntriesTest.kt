/**
 * [errorDetailEntries] 单测（docs/tech/08 §4.5 失败详情弹层）：覆盖字段取舍、
 * details 逐键一行、cause 只取类名与 message。
 */
package com.example.autop.feature.settings.me.models

import com.example.autop.core.common.AppError
import com.example.autop.feature.settings.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ErrorDetailEntriesTest {
    @Test
    fun `full error maps all fields in order`() {
        val error =
            AppError(
                code = "llm.auth",
                message = "bad key",
                hint = "check api key",
                details = mapOf("status" to "401"),
                cause = IllegalStateException("boom"),
            )
        val entries = errorDetailEntries(error)
        assertThat(entries.map { it.labelRes })
            .containsExactly(
                R.string.model_probe_detail_code,
                R.string.model_probe_detail_message,
                R.string.model_probe_detail_hint,
                R.string.model_probe_detail_context,
                R.string.model_probe_detail_cause,
            ).inOrder()
        assertThat(entries[0].value).isEqualTo("llm.auth")
        assertThat(entries[1].value).isEqualTo("bad key")
        assertThat(entries[2].value).isEqualTo("check api key")
        assertThat(entries[3].value).isEqualTo("status = 401")
        assertThat(entries[4].value).contains("IllegalStateException")
        assertThat(entries[4].value).contains("boom")
    }

    @Test
    fun `optional fields are omitted when absent`() {
        val entries = errorDetailEntries(AppError(code = "llm.timeout", message = "timed out"))
        assertThat(entries.map { it.labelRes })
            .containsExactly(R.string.model_probe_detail_code, R.string.model_probe_detail_message)
            .inOrder()
    }

    @Test
    fun `details map to one entry per key`() {
        val error =
            AppError(
                code = "llm.bad_response",
                message = "unparseable",
                details = linkedMapOf("path" to "/v1/chat", "body" to "truncated"),
            )
        val entries = errorDetailEntries(error)
        assertThat(entries.map { it.value })
            .containsExactly(
                "llm.bad_response",
                "unparseable",
                "path = /v1/chat",
                "body = truncated",
            ).inOrder()
    }
}
