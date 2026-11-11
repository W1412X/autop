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
        assertThat(schemaVersionOf(migrated)).isEqualTo(5)
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
