/**
 * 04 §6 项 14：JSON 回退协议——解析带前后说明文字的 JSON；解析失败注入
 * parse_error 重试，超限后按纯文本收尾；端到端走 TurnLoop 的回退链路。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.protocol.FallbackMode
import com.example.autop.agent.protocol.JsonParsed
import com.example.autop.agent.protocol.JsonToolProtocol
import com.example.autop.agent.protocol.ResolvedCalls
import com.example.autop.agent.runtime.testing.FakeTool
import com.example.autop.agent.runtime.testing.RuntimeHarness
import com.example.autop.agent.runtime.testing.ScriptedLlmProvider
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.ModelCapabilities
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class JsonFallbackTest {
    @Test
    fun `14a 带前后说明文字的 JSON 解析为工具调用`() {
        val text =
            "我先看看。\n```json\n" +
                """{"tool_calls":[{"name":"tap","arguments":{"target":"n1"}},{"name":"wait","arguments":{}}]}""" +
                "\n```\n完毕。"

        val parsed = JsonToolProtocol.parse(text, turn = 3)

        assertThat(parsed).isInstanceOf(JsonParsed.Calls::class.java)
        val calls = (parsed as JsonParsed.Calls).calls
        assertThat(calls.map { it.name }).containsExactly("tap", "wait").inOrder()
        assertThat(calls[0].id).isEqualTo("fb_3_0")
        assertThat(calls[0].argsJson).contains("n1")
        assertThat(calls[1].id).isEqualTo("fb_3_1")
    }

    @Test
    fun `14b final 文本解析为自然收尾`() {
        val parsed = JsonToolProtocol.parse("说明文字 {\"final\":\"完成了\"} 附注", turn = 1)

        assertThat(parsed).isEqualTo(JsonParsed.Final("完成了"))
    }

    @Test
    fun `14c 解析失败注入重试提示 超限后按纯文本收尾`() {
        val mode = FallbackMode(TestPromptRepository(), nativeTools = false, parseRetriesLimit = 2)

        val first = mode.resolveCalls(textMsg("这不是 JSON"), turn = 1, toolNames = TOOLS)
        assertThat(first).isInstanceOf(ResolvedCalls.Retry::class.java)
        assertThat((first as ResolvedCalls.Retry).injectText).isNotEmpty()

        val second = mode.resolveCalls(textMsg("{ broken json"), turn = 2, toolNames = TOOLS)
        assertThat(second).isInstanceOf(ResolvedCalls.Retry::class.java)

        val third = mode.resolveCalls(textMsg("还是不行"), turn = 3, toolNames = TOOLS)
        assertThat(third).isEqualTo(ResolvedCalls.Done(null))
    }

    @Test
    fun `14d 重试后解析成功继续调用`() {
        val mode = FallbackMode(TestPromptRepository(), nativeTools = false, parseRetriesLimit = 2)
        val retry = mode.resolveCalls(textMsg("没有 JSON"), turn = 1, toolNames = TOOLS)
        assertThat(retry).isInstanceOf(ResolvedCalls.Retry::class.java)

        val json = """{"tool_calls":[{"name":"tap","arguments":{}}]}"""
        val resolved = mode.resolveCalls(textMsg(json), turn = 2, toolNames = TOOLS)

        assertThat(resolved).isInstanceOf(ResolvedCalls.ToolCalls::class.java)
        assertThat((resolved as ResolvedCalls.ToolCalls).calls.single().name).isEqualTo("tap")
    }

    @Test
    fun `14e 端到端：回退链路解析调用 解析失败重试 后收尾`() =
        runTest {
            val llm =
                ScriptedLlmProvider(
                    profile =
                        ScriptedLlmProvider.TEST_PROFILE.copy(
                            capabilities = ModelCapabilities(tools = false),
                        ),
                )
            val tool = FakeTool()
            val h = RuntimeHarness(tools = setOf(tool), llm = llm)
            llm
                .say("我先操作。\n```json\n{\"tool_calls\":[{\"name\":\"fake_tool\",\"arguments\":{}}]}\n```")
                .say("抱歉，这次没按格式回答")
                .say("{\"final\":\"完成了\"}")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            assertThat(tool.invocations).hasSize(1)
            // 解析失败的重试提示以 INJECTED 注入
            val kinds =
                h.store
                    .payloadsOf(thread)
                    .filterIsInstance<UserMessage>()
                    .map { it.kind }
            assertThat(kinds).contains(UserMessageKind.INJECTED)
            // 历史降级：回退协议下没有 TOOL 角色消息
            assertThat(
                llm.requests
                    .last()
                    .messages
                    .none { it.role == Role.TOOL },
            ).isTrue()
        }

    @Test
    fun `14f 回退协议下工具结果进入下一轮请求 调用以协议 JSON 回放`() =
        runTest {
            val llm =
                ScriptedLlmProvider(
                    profile =
                        ScriptedLlmProvider.TEST_PROFILE.copy(
                            capabilities = ModelCapabilities(tools = false),
                        ),
                )
            val tool = FakeTool()
            val h = RuntimeHarness(tools = setOf(tool), llm = llm)
            llm
                .say("{\"tool_calls\":[{\"name\":\"fake_tool\",\"arguments\":{}}]}")
                .say("{\"final\":\"完成了\"}")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            // 落盘的助手消息带 ToolCall 块（工具结果才能配对，不被配对修复丢弃）
            val calls =
                h.store
                    .payloadsOf(thread)
                    .filterIsInstance<AssistantMessage>()
                    .flatMap { it.blocks.filterIsInstance<Block.ToolCall>() }
            assertThat(calls.map { it.name }).containsExactly("fake_tool")
            val texts =
                llm.requests[1]
                    .messages
                    .flatMap { msg -> msg.blocks.filterIsInstance<Block.Text>().map { msg.role to it.text } }
            // 工具结果作为 USER 文本发给模型
            assertThat(texts.any { (role, text) -> role == Role.USER && text.contains("fake-ok:tool.fake") }).isTrue()
            // 模型自己的调用按协议格式回放，且只出现一次
            val replayed = texts.filter { (role, text) -> role == Role.ASSISTANT && text.contains("tool_calls") }
            assertThat(replayed).hasSize(1)
            assertThat(replayed.single().second).isEqualTo("""{"tool_calls":[{"name":"fake_tool","arguments":{}}]}""")
        }

    @Test
    fun `14g 原生模式外层没有调用时 整段协议 JSON 正文当作调用`() {
        val mode = FallbackMode(TestPromptRepository(), nativeTools = true, parseRetriesLimit = 2)

        val resolved =
            mode.resolveCalls(
                textMsg("```json\n{\"tool_calls\":[{\"name\":\"tap\",\"arguments\":{\"x\":1}}]}\n```"),
                4,
                TOOLS,
            )

        assertThat(resolved).isInstanceOf(ResolvedCalls.ToolCalls::class.java)
        val calls = resolved as ResolvedCalls.ToolCalls
        assertThat(calls.fromText).isTrue()
        assertThat(calls.calls.single().name).isEqualTo("tap")
        // 落盘消息：正文换成 ToolCall 块
        val persisted = mode.persistable(textMsg("{\"tool_calls\":[{\"name\":\"tap\",\"arguments\":{}}]}"), calls)
        assertThat(persisted.blocks.single()).isInstanceOf(Block.ToolCall::class.java)
    }

    @Test
    fun `14h 原生模式正文只是提到 JSON 或工具名未知时不当调用`() {
        val mode = FallbackMode(TestPromptRepository(), nativeTools = true, parseRetriesLimit = 2)

        val example = "示例：{\"tool_calls\":[{\"name\":\"tap\",\"arguments\":{}}]} 这样写"
        val prose = mode.resolveCalls(textMsg(example), 1, TOOLS)
        val unknown = mode.resolveCalls(textMsg("{\"tool_calls\":[{\"name\":\"rm_rf\",\"arguments\":{}}]}"), 1, TOOLS)

        assertThat(prose).isEqualTo(ResolvedCalls.Done(null))
        assertThat(unknown).isEqualTo(ResolvedCalls.Done(null))
    }

    @Test
    fun `14i 两种模式都优先读外层调用块`() {
        val outer = Block.ToolCall("call_1", "tap", "{}")
        val inText = Block.Text("{\"tool_calls\":[{\"name\":\"wait\",\"arguments\":{}}]}")
        val msg = Msg(Role.ASSISTANT, listOf(inText, outer))
        for (native in listOf(true, false)) {
            val mode = FallbackMode(TestPromptRepository(), nativeTools = native, parseRetriesLimit = 2)
            val resolved = mode.resolveCalls(msg, 1, TOOLS) as ResolvedCalls.ToolCalls
            assertThat(resolved.calls).containsExactly(outer)
            assertThat(resolved.fromText).isFalse()
        }
    }

    @Test
    fun `14j 端到端：原生模式模型把调用写进正文 仍执行并配对结果`() =
        runTest {
            val tool = FakeTool()
            val h = RuntimeHarness(tools = setOf(tool))
            h.llm
                .say("{\"tool_calls\":[{\"name\":\"fake_tool\",\"arguments\":{}}]}")
                .say("完成了")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            assertThat(finished.summary).isEqualTo("完成了")
            assertThat(tool.invocations).hasSize(1)
            // 第二次请求按原生格式带上调用与结果
            val second = h.llm.requests[1].messages
            assertThat(second.any { m -> m.blocks.any { it is Block.ToolCall && it.name == "fake_tool" } }).isTrue()
            assertThat(second.any { it.role == Role.TOOL }).isTrue()
        }

    private fun textMsg(text: String): Msg = Msg(Role.ASSISTANT, listOf(Block.Text(text)))

    private companion object {
        val TOOLS = setOf("tap", "wait", "fake_tool")
    }
}
