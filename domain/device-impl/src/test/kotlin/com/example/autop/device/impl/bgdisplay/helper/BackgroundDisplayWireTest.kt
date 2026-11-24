package com.example.autop.device.impl.bgdisplay.helper

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class BackgroundDisplayWireTest {
    @Test
    fun `handshake and replies preserve protocol identity`() {
        val ready = BackgroundDisplayWire.ready(BackgroundDisplayWire.EXPECTED_UID)
        val success = BackgroundDisplayWire.success("request-1")
        val failure = BackgroundDisplayWire.failure(7, BackgroundDisplayWire.ERR_STALE_NODE)

        assertThat(ready.getInt("uid")).isEqualTo(2000)
        assertThat(ready.getInt("version")).isEqualTo(BackgroundDisplayWire.PROTOCOL_VERSION)
        assertThat(success.getString("id")).isEqualTo("request-1")
        assertThat(success.getBoolean("ok")).isTrue()
        assertThat(failure.getInt("id")).isEqualTo(7)
        assertThat(failure.getString("error")).isEqualTo(BackgroundDisplayWire.ERR_STALE_NODE)
    }

    @Test
    fun `request parsing rejects malformed and oversized values`() {
        val invalid =
            listOf(
                JSONObject(),
                JSONObject().put("id", JSONObject.NULL),
                JSONObject().put("id", true),
                JSONObject().put("id", "x".repeat(BackgroundDisplayWire.MAX_ID_CHARS + 1)),
                JSONObject().put("id", 1).put("value", 1.5),
                JSONObject().put("id", 1).put("codes", JSONArray(listOf(0))),
                JSONObject().put("id", 1).put("codes", JSONArray(List(9) { 4 })),
            )

        invalid.forEach { request ->
            val rejected =
                runCatching {
                    BackgroundDisplayWire.requestId(request)
                    if (request.has("value")) BackgroundDisplayWire.int(request, "value")
                    if (request.has("codes")) BackgroundDisplayWire.keyCodes(request)
                }.isFailure
            assertThat(rejected).isTrue()
        }
    }

    @Test
    fun `session ids reject blank and oversized values`() {
        val valid = JSONObject().put("sessionId", "run-a")

        assertThat(BackgroundDisplayWire.sessionId(valid)).isEqualTo("run-a")
        assertThat(
            runCatching {
                BackgroundDisplayWire.sessionId(JSONObject().put("sessionId", " "))
            }.isFailure,
        ).isTrue()
        assertThat(
            runCatching {
                BackgroundDisplayWire.sessionId(
                    JSONObject().put("sessionId", "x".repeat(BackgroundDisplayWire.MAX_ID_CHARS + 1)),
                )
            }.isFailure,
        ).isTrue()
    }

    @Test
    fun `package text and path limits use utf8 byte bounds`() {
        val request =
            JSONObject()
                .put("id", 1)
                .put("packageName", "com.example.app")
                .put("text", "文".repeat(BackgroundDisplayWire.MAX_TEXT_BYTES / 3))
                .put("path", JSONArray(listOf(0, 2, 5)))

        assertThat(BackgroundDisplayWire.packageName(request)).isEqualTo("com.example.app")
        assertThat(BackgroundDisplayWire.string(request, "text", BackgroundDisplayWire.MAX_TEXT_BYTES)).hasLength(
            BackgroundDisplayWire.MAX_TEXT_BYTES / 3,
        )
        assertThat(BackgroundDisplayWire.intArray(request, "path", maxSize = 50, min = 0)).containsExactly(0, 2, 5)
        assertThat(
            runCatching {
                BackgroundDisplayWire.packageName(JSONObject().put("packageName", "../bad"))
            }.isFailure,
        ).isTrue()
    }
}
