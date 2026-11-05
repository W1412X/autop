package com.example.autop.core.config

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ConfigSnapshotTest {
    private val defaults =
        buildJsonObject {
            put(
                "agent",
                buildJsonObject {
                    put(
                        "compaction",
                        buildJsonObject {
                            put("thresholdRatio", 0.75)
                            put(
                                "strategies",
                                kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("compaction.handoff"))),
                            )
                            put("retries", 2)
                            put("maxConsecutiveFailures", 3)
                            put("keepUserMessageTokens", 4000)
                            put("deterministicRecentCalls", 12)
                        },
                    )
                },
            )
        }

    private val keys = listOf(AgentConfig.COMPACTION)

    @Test
    fun `decodes section from merged tree`() {
        val overlay =
            buildJsonObject {
                put(
                    "agent",
                    buildJsonObject {
                        put("compaction", buildJsonObject { put("retries", 5) })
                    },
                )
            }
        val snapshot = buildConfigSnapshot(defaults, keys, listOf(overlay))
        assertThat(snapshot[AgentConfig.COMPACTION].retries).isEqualTo(5)
        assertThat(snapshot[AgentConfig.COMPACTION].thresholdRatio).isEqualTo(0.75)
        assertThat(snapshot.problems()).isEmpty()
    }

    @Test
    fun `validation failure falls back to default and reports problem`() {
        val overlay =
            buildJsonObject {
                put(
                    "agent",
                    buildJsonObject {
                        put("compaction", buildJsonObject { put("thresholdRatio", 1.5) })
                    },
                )
            }
        val snapshot = buildConfigSnapshot(defaults, keys, listOf(overlay))
        assertThat(snapshot[AgentConfig.COMPACTION].thresholdRatio).isEqualTo(0.75)
        assertThat(snapshot.problems()).hasSize(1)
        assertThat(snapshot.problems()[0]).contains("agent.compaction")
    }

    @Test
    fun `decode failure falls back to default and reports problem`() {
        val overlay =
            buildJsonObject {
                put(
                    "agent",
                    buildJsonObject {
                        put("compaction", buildJsonObject { put("retries", "not-a-number") })
                    },
                )
            }
        val snapshot = buildConfigSnapshot(defaults, keys, listOf(overlay))
        assertThat(snapshot[AgentConfig.COMPACTION].retries).isEqualTo(2)
        assertThat(snapshot.problems()).hasSize(1)
    }

    @Test
    fun `missing section falls back to default and reports problem`() {
        val snapshot = buildConfigSnapshot(buildJsonObject { put("other", 1) }, keys)
        assertThat(snapshot[AgentConfig.COMPACTION].retries).isEqualTo(2)
        assertThat(snapshot.problems()).hasSize(1)
        assertThat(snapshot.problems()[0]).contains("missing")
    }

    @Test
    fun `diffFromDefaults reflects overlay values only`() {
        val overlay =
            buildJsonObject {
                put(
                    "agent",
                    buildJsonObject {
                        put("compaction", buildJsonObject { put("retries", 5) })
                    },
                )
            }
        val snapshot = buildConfigSnapshot(defaults, keys, listOf(overlay))
        val diff = snapshot.diffFromDefaults()
        assertThat(diff.keys).containsExactly("agent")
        val agent = diff["agent"] as kotlinx.serialization.json.JsonObject
        assertThat(agent.keys).containsExactly("compaction")
        val compaction = agent["compaction"] as kotlinx.serialization.json.JsonObject
        assertThat(compaction.keys).containsExactly("retries")
        assertThat(compaction["retries"]).isEqualTo(JsonPrimitive(5))
    }

    @Test
    fun `multiple overlays stack in order`() {
        val taskOverlay =
            buildJsonObject {
                put("agent", buildJsonObject { put("compaction", buildJsonObject { put("retries", 4) }) })
            }
        val runOverlay =
            buildJsonObject {
                put("agent", buildJsonObject { put("compaction", buildJsonObject { put("retries", 9) }) })
            }
        val snapshot = buildConfigSnapshot(defaults, keys, listOf(taskOverlay, runOverlay))
        assertThat(snapshot[AgentConfig.COMPACTION].retries).isEqualTo(9)
    }

    @Test
    fun `unknown overlay keys are ignored by decoding`() {
        val overlay =
            buildJsonObject {
                put(
                    "agent",
                    buildJsonObject {
                        put("compaction", buildJsonObject { put("futureOption", true) })
                    },
                )
            }
        val snapshot = buildConfigSnapshot(defaults, keys, listOf(overlay))
        assertThat(snapshot.problems()).isEmpty()
    }
}
