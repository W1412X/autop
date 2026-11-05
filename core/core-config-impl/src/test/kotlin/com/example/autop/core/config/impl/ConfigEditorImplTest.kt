package com.example.autop.core.config.impl

import com.example.autop.core.common.Outcome
import com.example.autop.core.config.AllConfigKeys
import com.example.autop.core.config.ConfigErrorCodes
import com.example.autop.core.config.setPath
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class ConfigEditorImplTest {
    private val defaults = loadDefaultConfigTree(loadRealAssetFiles())
    private val store = FakeOverridesStore()
    private val editor = ConfigEditorImpl(defaults, AllConfigKeys.ALL, store, NoopLogger)

    @Test
    fun `setOverride writes value and clearOverride removes it`() =
        runTest {
            val ok = editor.setOverride("agent.compaction.retries", JsonPrimitive(5))
            assertThat(ok).isInstanceOf(Outcome.Ok::class.java)
            assertThat(editor.userOverrides())
                .isEqualTo(setPath(JsonObject(emptyMap()), "agent.compaction.retries", JsonPrimitive(5)))
            editor.clearOverride("agent.compaction.retries")
            assertThat(editor.userOverrides()).isEqualTo(JsonObject(emptyMap()))
        }

    @Test
    fun `unknown top level path is rejected`() =
        runTest {
            val err = editor.setOverride("nope.nope", JsonPrimitive(1))
            assertThat((err as Outcome.Err).error.code).isEqualTo(ConfigErrorCodes.UNKNOWN_PATH)
        }

    @Test
    fun `unknown field inside a section is rejected`() =
        runTest {
            val err = editor.setOverride("agent.compaction.nope", JsonPrimitive(1))
            assertThat((err as Outcome.Err).error.code).isEqualTo(ConfigErrorCodes.UNKNOWN_PATH)
            assertThat(editor.userOverrides()).isEqualTo(JsonObject(emptyMap()))
        }

    @Test
    fun `invalid value is rejected and nothing is written`() =
        runTest {
            val err = editor.setOverride("agent.compaction.thresholdRatio", JsonPrimitive(1.5))
            assertThat((err as Outcome.Err).error.code).isEqualTo(ConfigErrorCodes.INVALID)
            assertThat(editor.userOverrides()).isEqualTo(JsonObject(emptyMap()))
        }

    @Test
    fun `setting JsonNull clears the override`() =
        runTest {
            editor.setOverride("agent.compaction.retries", JsonPrimitive(5))
            val ok = editor.setOverride("agent.compaction.retries", JsonNull)
            assertThat(ok).isInstanceOf(Outcome.Ok::class.java)
            assertThat(editor.userOverrides()).isEqualTo(JsonObject(emptyMap()))
        }

    @Test
    fun `whole section override is accepted`() =
        runTest {
            val value = JsonObject(mapOf("retries" to JsonPrimitive(4)))
            val ok = editor.setOverride("agent.compaction", value)
            assertThat(ok).isInstanceOf(Outcome.Ok::class.java)
            assertThat(editor.userOverrides()).isEqualTo(setPath(JsonObject(emptyMap()), "agent.compaction", value))
        }

    @Test
    fun `top level scatter keys are editable`() =
        runTest {
            val ok = editor.setOverride("task.idleBeforeStartMs", JsonPrimitive(20000))
            assertThat(ok).isInstanceOf(Outcome.Ok::class.java)
            editor.clearOverride("task.idleBeforeStartMs")
            assertThat(editor.userOverrides()).isEqualTo(JsonObject(emptyMap()))
        }
}
