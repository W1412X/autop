/**
 * FIRST_USER 系统上下文落位（docs/tech/04 §3.2/§3.10，N174）：环境/触发/goal 等上下文
 * 单独成 INJECTED 消息（进模型、不进 UI 时间线与记忆），用户输入单独成 INPUT；
 * 无输入的 Run 不产生 INPUT 用户气泡。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.ContextContributor
import com.example.autop.agent.ContextPart
import com.example.autop.agent.ContextRequest
import com.example.autop.agent.Placement
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.runtime.testing.HarnessFakes
import com.example.autop.agent.runtime.testing.RuntimeHarness
import com.example.autop.llm.Block
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FirstUserContextTest {
    @Test
    fun `first user context is injected separately from user input`() =
        runTest {
            val h = harness()
            h.llm.say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread, input = listOf(Block.Text("发个短信"))).await()

            val user = h.store.payloadsOf(thread).filterIsInstance<UserMessage>()
            assertThat(user.map { it.kind })
                .containsExactly(UserMessageKind.INJECTED, UserMessageKind.INPUT)
                .inOrder()
            assertThat(textOf(user[0])).contains("电量 80%")
            assertThat(textOf(user[1])).isEqualTo("发个短信")
        }

    @Test
    fun `run without user input emits no input bubble`() =
        runTest {
            val h = harness()
            h.llm.say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread, input = emptyList()).await()

            val user = h.store.payloadsOf(thread).filterIsInstance<UserMessage>()
            assertThat(user.map { it.kind }).containsExactly(UserMessageKind.INJECTED)
        }

    private fun harness(): RuntimeHarness =
        RuntimeHarness(fakes = HarnessFakes(contributors = setOf(FakeOutputContributor())))

    private fun textOf(message: UserMessage): String =
        message.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
}

/** 假输出贡献者（形状同 `context.output`：FIRST_USER 系统上下文）；id 必须在默认启用清单内才会被调用。 */
private class FakeOutputContributor : ContextContributor {
    override val id: String = "context.output"

    override suspend fun contribute(req: ContextRequest): ContextPart =
        ContextPart(placement = Placement.FIRST_USER, text = "当前环境：电量 80%", promptIds = emptyMap())
}
