package com.example.autop.core.config.impl

import androidx.datastore.core.CorruptionException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** [ModelProfilesSerializer] 单测：解析、v1 迁移、损坏回退、schemaVersion 归一（docs/tech/03 §3）。 */
internal class ModelProfilesSerializerTest {
    private val serializer = ModelProfilesSerializer()

    @Test
    fun readFromParsesDocument() {
        val doc = runBlocking { serializer.readFrom(ByteArrayInputStream(docJson().toByteArray())) }
        assertThat(doc.schemaVersion).isEqualTo(MODEL_PROFILES_SCHEMA_VERSION)
        assertThat(doc.profiles).hasSize(1)
        assertThat(doc.profiles.first().id).isEqualTo(PROFILE_ID)
        assertThat(doc.defaultProfileId).isEqualTo(PROFILE_ID)
    }

    @Test
    fun readFromMigratesV1RolesMainToDefault() {
        val json =
            """{"schemaVersion": 1, "profiles": [], "roles": {"main": "$PROFILE_ID", "vision": "other"}}"""
        val doc = runBlocking { serializer.readFrom(ByteArrayInputStream(json.toByteArray())) }
        assertThat(doc.schemaVersion).isEqualTo(MODEL_PROFILES_SCHEMA_VERSION)
        assertThat(doc.defaultProfileId).isEqualTo(PROFILE_ID)
    }

    @Test
    fun readFromThrowsOnCorruptJson() {
        assertThrows(CorruptionException::class.java) {
            runBlocking { serializer.readFrom(ByteArrayInputStream("not json".toByteArray())) }
        }
    }

    @Test
    fun readFromThrowsOnNewerSchemaVersion() {
        val json = docJson(schemaVersion = MODEL_PROFILES_SCHEMA_VERSION + 1)
        assertThrows(CorruptionException::class.java) {
            runBlocking { serializer.readFrom(ByteArrayInputStream(json.toByteArray())) }
        }
    }

    @Test
    fun writeToNormalizesSchemaVersion() {
        val output = ByteArrayOutputStream()
        val doc = serializer.defaultValue.copy(schemaVersion = MODEL_PROFILES_SCHEMA_VERSION + 7)
        runBlocking { serializer.writeTo(doc, output) }
        val roundTrip = runBlocking { serializer.readFrom(ByteArrayInputStream(output.toByteArray())) }
        assertThat(roundTrip.schemaVersion).isEqualTo(MODEL_PROFILES_SCHEMA_VERSION)
    }

    private fun docJson(schemaVersion: Int = MODEL_PROFILES_SCHEMA_VERSION): String =
        """
        {
          "schemaVersion": $schemaVersion,
          "profiles": [
            {
              "id": "$PROFILE_ID",
              "displayName": "P1",
              "protocol": "openai",
              "baseUrl": "https://api.example.com",
              "apiKeyRef": "secret:model:$PROFILE_ID",
              "model": "m1",
              "contextWindow": 8192,
              "maxOutputTokens": 256,
              "capabilities": {"tools": true, "vision": true}
            }
          ],
          "defaultProfileId": "$PROFILE_ID"
        }
        """.trimIndent()

    private companion object {
        const val PROFILE_ID = "p1"
    }
}
