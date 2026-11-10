/**
 * 确定性压缩（agent-harness-overhaul WP4.7）：基线顺序
 * initialContextMsg → 用户消息 → 确定性说明 → 笔记 → 最后观测；goal 随 initialContext 存活。
 */
package com.example.autop.agent.runtime.compaction

import com.example.autop.agent.CompactionInput
import com.example.autop.agent.RenderItem
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.SystemPart
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DeterministicCompactorTest {
    @Test
    fun `baseline order is initial context, user messages, note, notes block, last observation`() =
        runTest {
            val compactor = DeterministicCompactor(TestPromptRepository())
            val input =
                input(
                    initialContext = "goal: 记账",
                    notes = listOf("用户名是小明"),
                    userMessages = listOf(1L to Msg(Role.USER, listOf(Block.Text("用户一")))),
                    lastObservation = render(2, "观测文本"),
                )

            val result = (compactor.compact(input) as Outcome.Ok).value

            val texts = result.baselineMessages.map { (it.blocks[0] as Block.Text).text }
            assertThat(texts[0]).contains("goal: 记账")
            assertThat(texts[1]).isEqualTo("用户一")
            assertThat(texts[2]).isNotEmpty()
            assertThat(texts[3]).contains("用户名是小明")
            assertThat(texts[4]).contains("观测文本")
        }

    @Test
    fun `goal survives deterministic fallback`() =
        runTest {
            val compactor = DeterministicCompactor(TestPromptRepository())

            val result =
                (compactor.compact(input(initialContext = "goal: 汇总账单")) as Outcome.Ok).value

            assertThat(result.baselineMessages.first().toString()).contains("goal: 汇总账单")
        }

    @Test
    fun `no initial context omits the message`() =
        runTest {
            val compactor = DeterministicCompactor(TestPromptRepository())

            val result =
                (compactor.compact(input(initialContext = null, notes = listOf("笔记"))) as Outcome.Ok).value

            assertThat(result.baselineMessages.first().toString()).doesNotContain("goal")
            assertThat(result.baselineMessages.last().toString()).contains("笔记")
        }

    private fun input(
        initialContext: String?,
        notes: List<String> = emptyList(),
        userMessages: List<Pair<Long, Msg>> = emptyList(),
        lastObservation: RenderItem? = null,
    ): CompactionInput =
        CompactionInput(
            threadId = ThreadId("t1"),
            system = listOf(SystemPart("系统提示")),
            live = listOfNotNull(lastObservation),
            userMessagesNewestFirst = userMessages,
            plan = emptyList(),
            runVars = emptyMap(),
            lastObservation = lastObservation,
            config = FakeConfigProvider().current(),
            initialContext = initialContext,
            notes = notes,
        )

    private fun render(
        seq: Long,
        text: String,
    ): RenderItem =
        RenderItem(
            seq,
            Msg(Role.USER, listOf(Block.Text(text))),
            null,
            null,
            fullBlocks = listOf(Block.Text(text)),
        )
}
