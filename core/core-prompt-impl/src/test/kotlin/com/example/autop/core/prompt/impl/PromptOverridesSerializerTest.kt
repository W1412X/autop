package com.example.autop.core.prompt.impl

import androidx.datastore.core.CorruptionException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PromptOverridesSerializerTest {
    private val serializer = PromptOverridesSerializer()

    @Test
    fun `reads overrides with schema version`() {
        val json = """{"schemaVersion":1,"overrides":{"x.tpl":"正文"}}"""
        val doc = runBlocking { serializer.readFrom(ByteArrayInputStream(json.encodeToByteArray())) }
        assertThat(doc.schemaVersion).isEqualTo(1)
        assertThat(doc.overrides).containsExactly("x.tpl", "正文")
    }

    @Test
    fun `invalid json becomes corruption exception with cause`() {
        val e =
            assertThrows(CorruptionException::class.java) {
                runBlocking { serializer.readFrom(ByteArrayInputStream("""{"schemaVersion":""".encodeToByteArray())) }
            }
        assertThat(e).hasCauseThat().isInstanceOf(SerializationException::class.java)
    }

    @Test
    fun `write then read round trips the document`() {
        val out = ByteArrayOutputStream()
        runBlocking { serializer.writeTo(PromptOverridesDoc(overrides = mapOf("a" to "b")), out) }
        val doc = runBlocking { serializer.readFrom(ByteArrayInputStream(out.toByteArray())) }
        assertThat(doc.overrides).containsExactly("a", "b")
        assertThat(doc.schemaVersion).isEqualTo(CURRENT_PROMPT_SCHEMA_VERSION)
    }
}
