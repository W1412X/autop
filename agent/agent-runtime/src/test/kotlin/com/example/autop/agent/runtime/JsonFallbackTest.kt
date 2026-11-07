/**
 * 04 §6 项 14：JSON 回退协议——解析带前后说明文字的 JSON；解析失败注入
 * parse_error 重试，超限后按纯文本收尾；端到端走 TurnLoop 的回退链路。
 */
package com.example.autop.agent.runtime

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

        val first = mode.resolveCalls(textMsg("这不是 JSON"), turn = 1)
        assertThat(first).isInstanceOf(ResolvedCalls.Retry::class.java)
        assertThat((first as ResolvedCalls.Retry).injectText).isNotEmpty()

        val second = mode.resolveCalls(textMsg("{ broken json"), turn = 2)
        assertThat(second).isInstanceOf(ResolvedCalls.Retry::class.java)

        val third = mode.resolveCalls(textMsg("还是不行"), turn = 3)
        assertThat(third).isEqualTo(ResolvedCalls.Done(null))
    }

    @Test
    fun `14d 重试后解析成功继续调用`() {
        val mode = FallbackMode(TestPromptRepository(), nativeTools = false, parseRetriesLimit = 2)
        assertThat(mode.resolveCalls(textMsg("没有 JSON"), turn = 1)).isInstanceOf(ResolvedCalls.Retry::class.java)

        val resolved = mode.resolveCalls(textMsg("""{"tool_calls":[{"name":"tap","arguments":{}}]}"""), turn = 2)

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
                            capabilities = ModelCapabilities(tools = false, vision = true, thinking = false),
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

    private fun textMsg(text: String): Msg = Msg(Role.ASSISTANT, listOf(Block.Text(text)))
}
