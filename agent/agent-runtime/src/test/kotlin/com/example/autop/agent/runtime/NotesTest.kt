/**
 * 04 §6 项 18（R1/B3）：note 写入后下一轮 system 末尾出现笔记块；压缩后笔记随
 * Baseline 保留；超过上限时丢弃最旧的（fork 保留见 ForkTest）。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.Baseline
import com.example.autop.agent.BaselineReason
import com.example.autop.agent.CompactionInput
import com.example.autop.agent.CompactionResult
import com.example.autop.agent.Compactor
import com.example.autop.agent.RunNoteAdded
import com.example.autop.agent.runtime.compaction.CompactionCoordinator
import com.example.autop.agent.runtime.compaction.CompactionOutcome
import com.example.autop.agent.runtime.notes.RunNotes
import com.example.autop.agent.runtime.testing.InMemoryThreadStore
import com.example.autop.agent.runtime.testing.RuntimeHarness
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.agent.runtime.tools.control.NoteTool
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.AgentNotesSection
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NotesTest {
    @Test
    fun `18a note 记录不再进 system（WP3 运行笔记）`() =
        runTest {
            val h = RuntimeHarness(tools = setOf(NoteTool(TestPromptRepository())))
            h.llm
                .call("note" to """{"text":"用户名是小明"}""")
                .say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread).await()

            // 运行笔记不再进 system：任何一轮都不出现，且 system 逐轮一致。
            for (request in h.llm.requests) {
                assertThat(request.system.none { it.text.contains("小明") }).isTrue()
            }
            assertThat(h.llm.requests[1].system).isEqualTo(h.llm.requests[0].system)
        }

    @Test
    fun `18b 超过 maxItems 上限时丢弃最旧的`() {
        val notes = RunNotes(InMemoryThreadStore(FakeClock(1_000L)), TestPromptRepository()) { text -> text.length }
        val msg = notes.blockMessage(listOf("第一条", "第二条", "第三条"), AgentNotesSection(maxItems = 2))
        val text =
            msg
                ?.blocks
                ?.filterIsInstance<Block.Text>()
                ?.joinToString("\n") { it.text }
                .orEmpty()
        assertThat(text).contains("第二条")
        assertThat(text).contains("第三条")
        assertThat(text).doesNotContain("第一条")
    }

    @Test
    fun `18c 压缩后笔记仍在`() =
        runTest {
            val store = InMemoryThreadStore(FakeClock(1_000L))
            val thread = store.createThread(null, null)
            store.append(
                thread,
                null,
                Baseline(
                    reason = BaselineReason.RESUME,
                    messages = listOf(Msg(Role.USER, listOf(Block.Text("旧内容")))),
                    notes = listOf("基线笔记"),
                ),
            )
            store.append(thread, null, RunNoteAdded(text = "新增笔记"))
            val notes = RunNotes(store, TestPromptRepository()) { text -> text.length }
            val coordinator = CompactionCoordinator(store, setOf(StubCompactor()), RunId("run-9"))

            val outcome = coordinator.compact(compactionInput(thread).copy(notes = notes.current(thread)))

            assertThat(outcome).isInstanceOf(CompactionOutcome.Done::class.java)
            val baseline = store.payloadsOf(thread).filterIsInstance<Baseline>().last()
            assertThat(baseline.reason).isEqualTo(BaselineReason.COMPACTION)
            // 压缩后 Baseline.notes 原样带入，当前笔记不变
            assertThat(baseline.notes).containsExactly("基线笔记", "新增笔记").inOrder()
            assertThat(notes.current(thread)).containsExactly("基线笔记", "新增笔记").inOrder()
        }

    private fun compactionInput(thread: ThreadId): CompactionInput =
        CompactionInput(
            threadId = thread,
            system = emptyList(),
            live = emptyList(),
            userMessagesNewestFirst = emptyList(),
            plan = emptyList(),
            runVars = emptyMap(),
            lastObservation = null,
            config = FakeConfigProvider().current(),
        )

    /** 成功的压缩策略占位（id 命中默认策略链）。 */
    private class StubCompactor : Compactor {
        override val id: String = "compaction.handoff"

        override suspend fun compact(input: CompactionInput): Outcome<CompactionResult> =
            Outcome.Ok(
                CompactionResult(
                    baselineMessages = listOf(Msg(Role.USER, listOf(Block.Text("压缩摘要")))),
                    summaryText = "压缩摘要",
                    coveredFromSeq = 1L,
                    coveredToSeq = 2L,
                ),
            )
    }
}
