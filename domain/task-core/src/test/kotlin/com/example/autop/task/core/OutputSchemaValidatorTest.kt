package com.example.autop.task.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputSchemaValidatorTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(text: String): JsonObject = json.decodeFromString(JsonObject.serializer(), text)

    @Test
    fun `passes when all required fields present`() {
        val outputs = obj("""{"city":"北京","temp":26}""")
        val schema =
            obj(
                """{"type":"object","properties":{"city":{"type":"string"},"temp":{"type":"integer"}},
                    "required":["city","temp"]}""",
            )
        assertEquals(emptyList<String>(), OutputSchemaValidator.validate(outputs, schema))
    }

    @Test
    fun `reports missing required field`() {
        val outputs = obj("""{"city":"北京"}""")
        val schema = obj("""{"type":"object","properties":{},"required":["city","temp"]}""")
        val problems = OutputSchemaValidator.validate(outputs, schema)
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("temp"))
    }

    @Test
    fun `reports wrong string type`() {
        val outputs = obj("""{"city":123}""")
        val schema = obj("""{"type":"object","properties":{"city":{"type":"string"}},"required":[]}""")
        val problems = OutputSchemaValidator.validate(outputs, schema)
        assertEquals(1, problems.size)
    }

    @Test
    fun `number accepts integer value`() {
        val outputs = obj("""{"temp":26}""")
        val schema = obj("""{"type":"object","properties":{"temp":{"type":"number"}},"required":[]}""")
        assertEquals(emptyList<String>(), OutputSchemaValidator.validate(outputs, schema))
    }

    @Test
    fun `integer rejects decimal value`() {
        val outputs = obj("""{"temp":26.5}""")
        val schema = obj("""{"type":"object","properties":{"temp":{"type":"integer"}},"required":[]}""")
        assertEquals(1, OutputSchemaValidator.validate(outputs, schema).size)
    }

    @Test
    fun `boolean check works`() {
        val outputs = obj("""{"ok":"true"}""")
        val schema = obj("""{"type":"object","properties":{"ok":{"type":"boolean"}},"required":[]}""")
        assertEquals(1, OutputSchemaValidator.validate(outputs, schema).size)
    }

    @Test
    fun `array and object checks work`() {
        val outputs = obj("""{"tags":["a"],"meta":{"k":"v"}}""")
        val schema =
            obj(
                """{"type":"object","properties":{"tags":{"type":"array"},"meta":{"type":"object"}},
                    "required":[]}""",
            )
        assertEquals(emptyList<String>(), OutputSchemaValidator.validate(outputs, schema))
    }

    @Test
    fun `null value rejected`() {
        val outputs = obj("""{"city":null}""")
        val schema = obj("""{"type":"object","properties":{"city":{"type":"string"}},"required":[]}""")
        assertEquals(1, OutputSchemaValidator.validate(outputs, schema).size)
    }

    @Test
    fun `non object schema rejected`() {
        val outputs = obj("""{}""")
        val schema = obj("""{"type":"string"}""")
        assertEquals(1, OutputSchemaValidator.validate(outputs, schema).size)
    }
}
