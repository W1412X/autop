package com.example.autop.core.config

import com.example.autop.core.common.Outcome
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class ConfigMigrationTest {
    @Test
    fun `runs migration chain step by step`() {
        val doc =
            buildJsonObject {
                put("schemaVersion", 1)
                put("agent", buildJsonObject { put("oldKey", "v") })
            }
        val migrations =
            listOf(
                ConfigMigration(from = 1, to = 2) { d -> setPath(d, "agent.newKey", JsonPrimitive("x")) },
                ConfigMigration(from = 2, to = 3) { d -> setPath(d, "agent.newKey", JsonPrimitive("y")) },
            )
        val result = migrateConfig(doc, migrations, targetVersion = 3)
        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        val migrated = (result as Outcome.Ok).value
        assertThat(schemaVersionOf(migrated)).isEqualTo(3)
        assertThat(resolvePath(migrated, "agent.newKey")).isEqualTo(JsonPrimitive("y"))
    }

    @Test
    fun `missing schemaVersion is treated as version 1`() {
        val doc = buildJsonObject { put("a", 1) }
        val result = migrateConfig(doc, emptyList(), targetVersion = 1)
        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
    }

    @Test
    fun `up to date doc passes through unchanged`() {
        val doc =
            buildJsonObject {
                put("schemaVersion", 1)
                put("a", 1)
            }
        val result = migrateConfig(doc, emptyList(), targetVersion = 1)
        assertThat((result as Outcome.Ok).value).isEqualTo(doc)
    }

    @Test
    fun `missing migration step fails with migration_failed`() {
        val doc = buildJsonObject { put("schemaVersion", 1) }
        val result = migrateConfig(doc, emptyList(), targetVersion = 3)
        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error.code).isEqualTo(ConfigErrorCodes.MIGRATION_FAILED)
    }

    @Test
    fun `newer than supported version fails with migration_failed`() {
        val doc = buildJsonObject { put("schemaVersion", 9) }
        val result = migrateConfig(doc, emptyList(), targetVersion = 3)
        assertThat((result as Outcome.Err).error.code).isEqualTo(ConfigErrorCodes.MIGRATION_FAILED)
    }

    @Test
    fun `throwing migration fails with migration_failed`() {
        val doc = buildJsonObject { put("schemaVersion", 1) }
        val migrations =
            listOf(
                ConfigMigration(from = 1, to = 2) { error("boom") },
            )
        val result = migrateConfig(doc, migrations, targetVersion = 2)
        assertThat((result as Outcome.Err).error.code).isEqualTo(ConfigErrorCodes.MIGRATION_FAILED)
    }

    @Test
    fun `v5 inserts node tools after tap and scroll`() {
        val doc =
            buildJsonObject {
                put("schemaVersion", 4)
                put("agent", toolsDoc(listOf("tool.tap", "tool.scroll")))
            }
        val migrated = okOf(migrateConfig(doc, builtinConfigMigrations()))
        assertThat(enabledIds(migrated))
            .containsExactly("tool.tap", "tool.tap_node", "tool.scroll", "tool.scroll_node")
            .inOrder()
    }

    @Test
    fun `v5 does not duplicate existing node tools`() {
        val doc =
            buildJsonObject {
                put("schemaVersion", 4)
                put("agent", toolsDoc(listOf("tool.tap", "tool.tap_node", "tool.scroll", "tool.scroll_node")))
            }
        val migrated = okOf(migrateConfig(doc, builtinConfigMigrations()))
        assertThat(enabledIds(migrated))
            .containsExactly("tool.tap", "tool.tap_node", "tool.scroll", "tool.scroll_node")
            .inOrder()
    }

    @Test
    fun `v5 removes device nodeAction and keeps docs without agent tools`() {
        val doc =
            buildJsonObject {
                put("schemaVersion", 4)
                put("device", buildJsonObject { put("nodeAction", buildJsonObject { put("enabled", true) }) })
            }
        val migrated = okOf(migrateConfig(doc, builtinConfigMigrations()))
        assertThat(migrated["device"]!!.jsonObject).doesNotContainKey("nodeAction")
        assertThat(schemaVersionOf(migrated)).isEqualTo(CURRENT_SCHEMA_VERSION)
    }

    @Test
    fun `v6 inserts intent tool after open_uri and navigation state after observe`() {
        val doc =
            buildJsonObject {
                put("schemaVersion", 5)
                put("agent", agentDoc(TOOLS_V5, SOURCES_V5))
            }
        val migrated = okOf(migrateConfig(doc, builtinConfigMigrations()))
        // v6 插入 tool.intent；继续迁移到 v7 时 tool.open_uri 被删除（tool_optimize D6）。
        assertThat(enabledIds(migrated)).containsExactly("tool.intent", "tool.wait").inOrder()
        assertThat(sourceIds(migrated))
            .containsExactly("state.environment", "state.observe", "state.navigation", "state.budget")
            .inOrder()
        assertThat(schemaVersionOf(migrated)).isEqualTo(CURRENT_SCHEMA_VERSION)
    }

    @Test
    fun `v6 does not duplicate existing intent entries`() {
        val tools = listOf("tool.open_uri", "tool.intent", "tool.wait")
        val sources = listOf("state.observe", "state.navigation")
        val doc =
            buildJsonObject {
                put("schemaVersion", 5)
                put("agent", agentDoc(tools, sources))
            }
        val migrated = okOf(migrateConfig(doc, builtinConfigMigrations()))
        // v7 删除 tool.open_uri（tool_optimize D6），其余顺序不变。
        assertThat(enabledIds(migrated)).containsExactly("tool.intent", "tool.wait").inOrder()
        assertThat(sourceIds(migrated)).isEqualTo(sources)
    }

    @Test
    fun `v6 keeps arrays without anchors and skips missing arrays`() {
        val doc =
            buildJsonObject {
                put("schemaVersion", 5)
                put("agent", agentDoc(listOf("tool.wait"), null))
            }
        val migrated = okOf(migrateConfig(doc, builtinConfigMigrations()))
        assertThat(enabledIds(migrated)).containsExactly("tool.wait")
        assertThat(resolvePath(migrated, "agent.state")).isNull()
    }

    @Test
    fun `v7 removes deleted config paths and keeps the rest`() {
        val doc =
            buildJsonObject {
                put("schemaVersion", 6)
                put(
                    "agent",
                    buildJsonObject {
                        put(
                            "breaker",
                            buildJsonObject {
                                put("stuckWarn", 3)
                                put("sameErrorWarn", 3)
                            },
                        )
                    },
                )
                put(
                    "device",
                    buildJsonObject {
                        put(
                            "serializer",
                            buildJsonObject {
                                put("diffMaxRatio", 0.4)
                                put("textMaxChars", -1)
                            },
                        )
                    },
                )
                put(
                    "flow",
                    buildJsonObject {
                        put("author", buildJsonObject { put("analysis", buildJsonObject { put("role", "main") }) })
                    },
                )
            }
        val migrated = okOf(migrateConfig(doc, builtinConfigMigrations()))
        val breaker = resolvePath(migrated, "agent.breaker")!!.jsonObject
        assertThat(breaker).doesNotContainKey("stuckWarn")
        assertThat(breaker).containsKey("sameErrorWarn")
        val serializer = resolvePath(migrated, "device.serializer")!!.jsonObject
        assertThat(serializer).doesNotContainKey("diffMaxRatio")
        assertThat(serializer).containsKey("textMaxChars")
        assertThat(resolvePath(migrated, "flow.author.analysis.role")).isNull()
        assertThat(schemaVersionOf(migrated)).isEqualTo(CURRENT_SCHEMA_VERSION)
    }

    @Test
    fun `v7 removes removed tools from enabled keeping order`() {
        val tools = listOf("tool.open_uri", "tool.tap", "tool.dismiss_popup", "tool.wait")
        val doc =
            buildJsonObject {
                put("schemaVersion", 6)
                put("agent", agentDoc(tools, null))
            }
        val migrated = okOf(migrateConfig(doc, builtinConfigMigrations()))
        assertThat(enabledIds(migrated)).containsExactly("tool.tap", "tool.wait").inOrder()
    }

    @Test
    fun `v7 removes dismiss_popup recovery from ladder`() {
        val ladder = listOf("recovery.wait_stable", "recovery.dismiss_popup", "recovery.back_and_retry")
        val doc =
            buildJsonObject {
                put("schemaVersion", 6)
                put(
                    "flow",
                    buildJsonObject {
                        put(
                            "recovery",
                            buildJsonObject {
                                put("ladder", JsonArray(ladder.map { JsonPrimitive(it) }))
                            },
                        )
                    },
                )
            }
        val migrated = okOf(migrateConfig(doc, builtinConfigMigrations()))
        val migratedLadder = resolvePath(migrated, "flow.recovery.ladder") as JsonArray
        assertThat(migratedLadder.map { it.jsonPrimitive.content })
            .containsExactly("recovery.wait_stable", "recovery.back_and_retry")
            .inOrder()
    }

    @Test
    fun `v7 keeps docs with missing paths unchanged`() {
        val doc =
            buildJsonObject {
                put("schemaVersion", 6)
                put("device", buildJsonObject { put("capture", buildJsonObject { put("timeoutMs", 1500) }) })
            }
        val migrated = okOf(migrateConfig(doc, builtinConfigMigrations()))
        assertThat(resolvePath(migrated, "device.capture.timeoutMs")).isEqualTo(JsonPrimitive(1500))
        assertThat(schemaVersionOf(migrated)).isEqualTo(CURRENT_SCHEMA_VERSION)
    }

    @Test
    fun `v1 doc migrates all the way to v7`() {
        val doc =
            buildJsonObject {
                put("schemaVersion", 1)
                put(
                    "agent",
                    buildJsonObject {
                        put(
                            "tools",
                            buildJsonObject {
                                put(
                                    "enabled",
                                    JsonArray(
                                        listOf(
                                            JsonPrimitive("tool.find_nodes"),
                                            JsonPrimitive("tool.open_uri"),
                                            JsonPrimitive("tool.dismiss_popup"),
                                            JsonPrimitive("tool.tap"),
                                        ),
                                    ),
                                )
                            },
                        )
                    },
                )
                put("device", buildJsonObject { put("nodeAction", buildJsonObject { put("enabled", true) }) })
            }
        val migrated = okOf(migrateConfig(doc, builtinConfigMigrations()))
        assertThat(schemaVersionOf(migrated)).isEqualTo(CURRENT_SCHEMA_VERSION)
        // v5 会在 tool.tap 之后补 tool.tap_node；v6 在 tool.open_uri 后插入 tool.intent（随后 v7 删 open_uri）。
        assertThat(enabledIds(migrated)).containsExactly("tool.intent", "tool.tap", "tool.tap_node").inOrder()
    }

    private fun agentDoc(
        tools: List<String>?,
        sources: List<String>?,
    ): JsonObject =
        buildJsonObject {
            if (tools !=
                null
            ) {
                put("tools", buildJsonObject { put("enabled", JsonArray(tools.map { JsonPrimitive(it) })) })
            }
            if (sources != null) {
                put("state", buildJsonObject { put("sources", JsonArray(sources.map { JsonPrimitive(it) })) })
            }
        }

    private fun sourceIds(doc: JsonObject): List<String> {
        val sources = resolvePath(doc, "agent.state.sources") as JsonArray
        return sources.map { it.jsonPrimitive.content }
    }

    private companion object {
        val TOOLS_V5 = listOf("tool.open_uri", "tool.wait")
        val SOURCES_V5 = listOf("state.environment", "state.observe", "state.budget")
    }

    private fun toolsDoc(ids: List<String>): JsonObject =
        buildJsonObject {
            put(
                "tools",
                buildJsonObject {
                    put("enabled", JsonArray(ids.map { JsonPrimitive(it) }))
                },
            )
        }

    private fun enabledIds(doc: JsonObject): List<String> {
        val enabled = resolvePath(doc, "agent.tools.enabled") as JsonArray
        return enabled.map { it.jsonPrimitive.content }
    }

    private fun okOf(result: Outcome<JsonObject>): JsonObject =
        when (result) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> error("expected ok, was ${result.error.code}")
        }
}
