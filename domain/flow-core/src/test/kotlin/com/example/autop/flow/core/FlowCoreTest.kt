package com.example.autop.flow.core

import com.example.autop.agent.ToolAccess
import com.example.autop.device.ObserveMode
import com.example.autop.flow.BreakNode
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowIssue
import com.example.autop.flow.IssueSeverity
import com.example.autop.flow.LlmOutput
import com.example.autop.flow.LlmOutputType
import com.example.autop.flow.Pick
import com.example.autop.flow.SetNode
import com.example.autop.flow.Target
import com.example.autop.flow.ToolNode
import com.example.autop.flow.Validator
import com.example.autop.flow.core.screen.ScreenJsonParser
import com.example.autop.flow.core.screen.TargetMatch
import com.example.autop.flow.core.screen.TargetMatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowCoreTest {
    private val screenJson =
        """
        {"screen":{"v":1,"mode":"a11y","untrusted":true,"windows":[
        {"app":"微信","page":"聊天","active":true,"kids":[
        {"tag":"text","text":"张三 · 明天开会吗","act":["click"],"bounds":[0,100,1000,200],"at":[500,150]},
        {"tag":"button","text":"发送","act":["click"],"bounds":[800,900,1000,1000],"at":[900,950]}
        ]}],"quality":{"nodes":2,"interactive":2}}}
        建议换用 visual
        """.trimIndent()

    @Test
    fun `parser reads windows and ignores trailing suggestion line`() {
        val model = ScreenJsonParser.parse(screenJson)
        assertEquals(ObserveMode.A11Y, model.mode)
        assertEquals(2, model.flatten().size)
        assertEquals(2, model.quality.interactive)
    }

    @Test
    fun `segment match finds a list item by one part of its joined text`() {
        val model = ScreenJsonParser.parse(screenJson)
        val match = TargetMatcher.resolve(model, Target(value = "张三"), "张三")
        val hit = (match as TargetMatch.Found).hits.single()
        assertEquals(500, hit.point.x)
        assertEquals(150, hit.point.y)
    }

    @Test
    fun `unique pick reports ambiguity and first pick resolves it`() {
        val model = ScreenJsonParser.parse(screenJson)
        val any = Target(value = ".", match = com.example.autop.flow.MatchMode.REGEX)
        assertTrue(TargetMatcher.resolve(model, any, ".") is TargetMatch.Ambiguous)
        assertTrue(TargetMatcher.resolve(model, any.copy(pick = Pick.FIRST), ".") is TargetMatch.Found)
    }

    @Test
    fun `missing target is not found`() {
        val model = ScreenJsonParser.parse(screenJson)
        assertTrue(TargetMatcher.resolve(model, Target(value = "不存在"), "不存在") is TargetMatch.NotFound)
    }

    @Test
    fun `label output accepts only declared labels`() {
        val output = LlmOutput(type = LlmOutputType.LABEL, labels = listOf("要回复", "忽略"))
        assertEquals(Parsed.Value(JsonPrimitive("忽略")), LlmOutputParser.parse("忽略。", output))
        assertTrue(LlmOutputParser.parse("不确定", output) is Parsed.Invalid)
    }

    @Test
    fun `json output is extracted from a fenced block and checked against schema`() {
        val schema =
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"type":"object","required":["a"]}""",
            ) as JsonObject
        val output = LlmOutput(type = LlmOutputType.JSON, schema = schema)
        assertTrue(LlmOutputParser.parse("```json\n{\"a\":1}\n```", output) is Parsed.Value)
        assertTrue(LlmOutputParser.parse("{\"b\":1}", output) is Parsed.Invalid)
    }

    @Test
    fun `validators report the first failing rule`() {
        val problem =
            LlmOutputParser.validate(JsonPrimitive("过长的回复内容"), listOf(Validator.MaxLen(3, "太长"))) { _, _ -> true }
        assertEquals("太长", problem)
    }

    private val catalog =
        object : FlowCatalog {
            override fun toolAccess(name: String): ToolAccess? = if (name == "tap") ToolAccess.WRITE_SCREEN else null

            override fun flow(nameOrId: String): FlowDefinition? = null

            override fun endpointRegistered(name: String): Boolean = false
        }

    private fun issues(vararg steps: com.example.autop.flow.Node): List<FlowIssue> =
        FlowValidator(catalog).validate(FlowDefinition(id = "f", name = "t", steps = steps.toList()))

    @Test
    fun `validator rejects break outside loops and duplicate ids`() {
        val found = issues(BreakNode(id = "a"), BreakNode(id = "a"))
        assertTrue(found.any { it.message.contains("循环") })
        assertTrue(found.any { it.message.contains("重复") })
    }

    @Test
    fun `validator rejects forbidden and unknown tools but accepts known ones`() {
        assertTrue(issues(ToolNode(id = "a", tool = "finish")).any { it.severity == IssueSeverity.ERROR })
        assertTrue(issues(ToolNode(id = "a", tool = "nope")).any { it.severity == IssueSeverity.ERROR })
        assertTrue(issues(ToolNode(id = "a", tool = "tap")).none { it.severity == IssueSeverity.ERROR })
    }

    @Test
    fun `validator warns about variables used before definition`() {
        val found = issues(SetNode(id = "a", vars = mapOf("x" to "y + 1")))
        assertTrue(found.any { it.severity == IssueSeverity.WARNING })
    }

    @Test
    fun `analyzer treats target based tool nodes as needing the screen`() {
        val flow =
            FlowDefinition(
                id = "f",
                name = "t",
                steps = listOf(ToolNode(id = "a", tool = "tap", target = Target(value = "ok"))),
            )
        assertTrue(FlowAnalyzer.needsScreen(flow, catalog) { true })
        val quiet = FlowDefinition(id = "f", name = "t", steps = listOf(SetNode(id = "a", vars = mapOf("x" to "1"))))
        assertEquals(false, FlowAnalyzer.needsScreen(quiet, catalog) { true })
    }
}
