/**
 * 运行时定义替换的验收用例（docs/design/flows-precondition-selfrepair.md S3/S5）。
 *
 * 只允许替换节点内容：节点结构与 id 不变；替换在节点执行前生效，后续节点用新定义。
 */
package com.example.autop.flow.core

import com.example.autop.core.common.Outcome
import com.example.autop.flow.Expect
import com.example.autop.flow.ExpectKind
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowSettings
import com.example.autop.flow.ToolNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowPatchTest {
    private val h = FlowTestHarness()

    private fun node(
        id: String,
        text: String,
        expect: Expect? = null,
    ) = ToolNode(
        id = id,
        tool = "say",
        args = JsonObject(mapOf("text" to JsonPrimitive(text))),
        expect = expect,
    )

    private fun flowOf(steps: List<ToolNode>) = FlowDefinition(id = "f1", name = "t", steps = steps)

    @Test
    fun `patch with the same node ids is accepted`() {
        h.run(listOf(node("n1", "a"), node("n2", "b")))
        val interpreter = h.lastInterpreter
        requireNotNull(interpreter)
        val patched = flowOf(listOf(node("n1", "a"), node("n2", "B")))
        val result = kotlinx.coroutines.runBlocking { interpreter.applyPatch("", patched) }
        assertTrue(result is Outcome.Ok)
    }

    @Test
    fun `adding a node is rejected`() {
        h.run(listOf(node("n1", "a")))
        val interpreter = h.lastInterpreter
        requireNotNull(interpreter)
        val patched = flowOf(listOf(node("n1", "a"), node("n2", "b")))
        val result = kotlinx.coroutines.runBlocking { interpreter.applyPatch("", patched) }
        assertTrue(result is Outcome.Err)
        assertEquals(FlowErrorCodes.INVALID, (result as Outcome.Err).error.code)
    }

    @Test
    fun `changing a semantic field is rejected`() {
        h.run(listOf(node("n1", "a", expect = Expect(kind = ExpectKind.TEXT, text = "发布成功"))))
        val interpreter = h.lastInterpreter
        requireNotNull(interpreter)
        val patched = flowOf(listOf(node("n1", "a")))
        val result = kotlinx.coroutines.runBlocking { interpreter.applyPatch("", patched) }
        assertTrue(result is Outcome.Err)
    }

    @Test
    fun `changing the flow settings is rejected`() {
        h.run(listOf(node("n1", "a")))
        val interpreter = h.lastInterpreter
        requireNotNull(interpreter)
        val patched = flowOf(listOf(node("n1", "a"))).copy(settings = FlowSettings(pauseAfterConsecutiveFailures = 9))
        val result = kotlinx.coroutines.runBlocking { interpreter.applyPatch("", patched) }
        assertTrue(result is Outcome.Err)
    }

    @Test
    fun `later nodes run with the patched definition`() {
        h.onToolCall = { tool ->
            if (tool == "say") {
                val interpreter = h.lastInterpreter
                requireNotNull(interpreter)
                val patched = flowOf(listOf(node("n1", "a"), node("n2", "B")))
                interpreter.applyPatch("", patched)
            }
        }
        h.run(listOf(node("n1", "a"), node("n2", "b")))
        assertEquals(listOf("say:a", "say:B"), h.toolCalls)
    }

    @Test
    fun `patching an unknown flow is rejected`() {
        h.run(listOf(node("n1", "a")))
        val interpreter = h.lastInterpreter
        requireNotNull(interpreter)
        val patched = flowOf(listOf(node("n1", "a")))
        val result = kotlinx.coroutines.runBlocking { interpreter.applyPatch("other", patched) }
        assertTrue(result is Outcome.Err)
        assertEquals(FlowErrorCodes.NOT_FOUND, (result as Outcome.Err).error.code)
    }
}
