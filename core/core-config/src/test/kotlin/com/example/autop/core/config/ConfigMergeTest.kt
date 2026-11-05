package com.example.autop.core.config

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ConfigMergeTest {
    @Test
    fun `deep merge merges nested objects and replaces scalars`() {
        val base =
            buildJsonObject {
                put(
                    "agent",
                    buildJsonObject {
                        put(
                            "budget",
                            buildJsonObject {
                                put("maxSteps", 40)
                                put("maxScreenshots", 20)
                            },
                        )
                        put("thinking", buildJsonObject { put("enabled", false) })
                    },
                )
            }
        val overlay =
            buildJsonObject {
                put(
                    "agent",
                    buildJsonObject {
                        put("budget", buildJsonObject { put("maxSteps", 10) })
                    },
                )
            }
        val merged = merge(base, overlay)
        val budget = resolvePath(merged, "agent.budget")
        assertThat(budget).isNotNull()
        assertThat((budget as kotlinx.serialization.json.JsonObject)["maxSteps"])
            .isEqualTo(JsonPrimitive(10))
        assertThat(budget["maxScreenshots"]).isEqualTo(JsonPrimitive(20))
        assertThat(resolvePath(merged, "agent.thinking.enabled")).isEqualTo(JsonPrimitive(false))
    }

    @Test
    fun `json null in overlay removes key`() {
        val base =
            buildJsonObject {
                put("a", 1)
                put("b", 2)
            }
        val overlay = buildJsonObject { put("a", JsonNull) }
        val merged = merge(base, overlay)
        assertThat(merged.containsKey("a")).isFalse()
        assertThat(merged["b"]).isEqualTo(JsonPrimitive(2))
    }

    @Test
    fun `arrays are replaced wholesale`() {
        val base = buildJsonObject { put("list", buildJsonArray(1, 2, 3)) }
        val overlay = buildJsonObject { put("list", buildJsonArray(9)) }
        val merged = merge(base, overlay)
        assertThat(merged["list"]).isEqualTo(buildJsonArray(9))
    }

    @Test
    fun `resolvePath walks dotted segments`() {
        val tree =
            buildJsonObject {
                put("agent", buildJsonObject { put("compaction", buildJsonObject { put("retries", 2) }) })
            }
        assertThat(resolvePath(tree, "agent.compaction.retries")).isEqualTo(JsonPrimitive(2))
        assertThat(resolvePath(tree, "agent.compaction.nope")).isNull()
        assertThat(resolvePath(tree, "missing.path")).isNull()
    }

    @Test
    fun `setPath writes nested value without mutating source`() {
        val tree = buildJsonObject { put("a", buildJsonObject { put("b", 1) }) }
        val updated = setPath(tree, "a.b", JsonPrimitive(2))
        assertThat(resolvePath(updated, "a.b")).isEqualTo(JsonPrimitive(2))
        assertThat(resolvePath(tree, "a.b")).isEqualTo(JsonPrimitive(1))
    }

    @Test
    fun `diffJson keeps only changed added and removed keys`() {
        val defaults =
            buildJsonObject {
                put("a", 1)
                put(
                    "b",
                    buildJsonObject {
                        put("x", 1)
                        put("y", 2)
                    },
                )
                put("gone", true)
            }
        val actual =
            buildJsonObject {
                put("a", 1)
                put(
                    "b",
                    buildJsonObject {
                        put("x", 5)
                        put("y", 2)
                    },
                )
                put("added", "s")
            }
        val diff = diffJson(defaults, actual)
        assertThat(diff.keys).containsExactly("b", "added", "gone")
        assertThat((diff["b"] as kotlinx.serialization.json.JsonObject).keys).containsExactly("x")
    }

    private fun buildJsonArray(vararg values: Int) =
        kotlinx.serialization.json.JsonArray(values.map { JsonPrimitive(it) })
}
