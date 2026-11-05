package com.example.autop.core.config.impl

import app.cash.turbine.test
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.AllConfigKeys
import com.example.autop.core.config.CURRENT_SCHEMA_VERSION
import com.example.autop.core.config.putSchemaVersion
import com.example.autop.core.config.setPath
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class ConfigProviderImplTest {
    private val defaults = loadDefaultConfigTree(loadRealAssetFiles())
    private val store = FakeOverridesStore()

    private fun newProvider() =
        ConfigProviderImpl(defaults, AllConfigKeys.ALL, store, TestDispatcherProvider(), NoopLogger)

    private suspend fun setOverrideInStore(
        path: String,
        value: JsonPrimitive,
    ) {
        store.update { doc ->
            putSchemaVersion(setPath(stripSchemaVersion(doc), path, value), CURRENT_SCHEMA_VERSION)
        }
    }

    @Test
    fun `current reflects user overrides`() =
        runTest {
            val provider = newProvider()
            assertThat(provider.current()[AgentConfig.COMPACTION].retries).isEqualTo(2)
            setOverrideInStore("agent.compaction.retries", JsonPrimitive(9))
            assertThat(provider.current()[AgentConfig.COMPACTION].retries).isEqualTo(9)
        }

    @Test
    fun `observe emits rebuilt snapshots on override change`() =
        runTest {
            val provider = newProvider()
            provider.observe().test {
                assertThat(awaitItem()[AgentConfig.COMPACTION].retries).isEqualTo(2)
                setOverrideInStore("agent.compaction.retries", JsonPrimitive(9))
                assertThat(awaitItem()[AgentConfig.COMPACTION].retries).isEqualTo(9)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `layered stacks overlays over user overrides without changing global`() =
        runTest {
            val provider = newProvider()
            setOverrideInStore("agent.compaction.retries", JsonPrimitive(5))
            val runOverlay =
                JsonObject(
                    mapOf(
                        "agent" to
                            JsonObject(mapOf("compaction" to JsonObject(mapOf("retries" to JsonPrimitive(9))))),
                    ),
                )
            val layered = provider.layered(runOverlay)
            assertThat(layered[AgentConfig.COMPACTION].retries).isEqualTo(9)
            assertThat(provider.current()[AgentConfig.COMPACTION].retries).isEqualTo(5)
        }

    @Test
    fun `diffFromDefaults contains only overridden leaves`() =
        runTest {
            val provider = newProvider()
            setOverrideInStore("agent.compaction.retries", JsonPrimitive(9))
            val diff = provider.current().diffFromDefaults()
            val agent = diff["agent"] as JsonObject
            val compaction = agent["compaction"] as JsonObject
            assertThat(compaction.keys).containsExactly("retries")
            assertThat(compaction["retries"]).isEqualTo(JsonPrimitive(9))
        }
}
