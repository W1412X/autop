package com.example.autop.core.config

import com.example.autop.core.common.Outcome
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
}
