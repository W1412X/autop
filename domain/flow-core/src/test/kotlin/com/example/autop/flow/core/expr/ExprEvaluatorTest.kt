package com.example.autop.flow.core.expr

import com.example.autop.flow.FlowErrorCodes
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.ZoneId

class ExprEvaluatorTest {
    private val vars =
        mutableMapOf<String, JsonElement>(
            "count" to JsonPrimitive(3),
            "name" to JsonPrimitive("张三"),
        )
    private val env =
        object : ExprEnv {
            override fun lookup(name: String): JsonElement? = vars[name]

            override fun nowMs(): Long = 0

            override fun random(
                lo: Double,
                hi: Double,
            ): Double = lo

            override val zone: ZoneId = ZoneId.of("UTC")
        }
    private val evaluator = ExprEvaluator(env, maxSteps = 1000)
    private val templates = TemplateRenderer(evaluator)

    private fun eval(s: String) = evaluator.eval(s)

    @Test
    fun `arithmetic keeps integer form and respects precedence`() {
        assertEquals(JsonPrimitive(7), eval("1 + 2 * 3"))
        assertEquals(JsonPrimitive(4), eval("count + 1"))
    }

    @Test
    fun `string concatenation and comparison`() {
        assertEquals(JsonPrimitive("a1"), eval("'a' + 1"))
        assertEquals(JsonPrimitive(true), eval("'3' == 3"))
        assertEquals(JsonPrimitive(true), eval("count >= 3 && name == '张三'"))
        assertEquals(JsonPrimitive(true), eval("count != 4"))
    }

    @Test
    fun `lists objects and functions`() {
        assertEquals(JsonPrimitive(2), eval("len([1, 2])"))
        assertEquals(JsonPrimitive(5), eval("get({'a': {'b': 5}}, 'a.b')"))
        assertEquals(JsonPrimitive("b"), eval("[\"a\", \"b\"][1]"))
        assertEquals(JsonPrimitive("x"), eval("coalesce(null, 'x')"))
    }

    @Test
    fun `undefined variable fails with stable code`() {
        val e = assertThrows(FlowException::class.java) { eval("missing + 1") }
        assertEquals(FlowErrorCodes.VAR_UNDEFINED, e.code)
    }

    @Test
    fun `template keeps type for sole expression and concatenates otherwise`() {
        assertEquals(JsonPrimitive(3), templates.render("{{ count }}"))
        assertEquals(JsonPrimitive("你好 张三!"), templates.render("你好 {{ name }}!"))
        assertEquals(JsonPrimitive("{{ x }}"), templates.render("\\{{ x }}"))
    }

    @Test
    fun `step limit stops runaway expressions`() {
        val small = ExprEvaluator(env, maxSteps = 3)
        assertThrows(FlowException::class.java) { small.eval("1+1+1+1+1+1") }
    }
}
