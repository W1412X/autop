/**
 * 04 §6 项 13：fork 后新 Thread 的 live 与源 Thread 在 atSeq 处一致（含笔记随
 * Baseline(FORK) 保留）；未配对 ToolCall 的点不允许 fork。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.Baseline
import com.example.autop.agent.BaselineReason
import com.example.autop.agent.ThreadCreated
import com.example.autop.agent.runtime.testing.FakeTool
import com.example.autop.agent.runtime.testing.HarnessFakes
import com.example.autop.agent.runtime.testing.RuntimeHarness
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.agent.runtime.testing.defaultRenderRules
import com.example.autop.agent.runtime.tools.control.NoteTool
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ForkTest {
    @Test
    fun `13a fork 后新 Thread 的 live 与源 Thread 在 atSeq 处一致`() =
        runTest {
            val h = harness()
            h.llm
                .call("note" to """{"text":"用户名是小明"}""")
                .call(FakeTool.FAKE_NAME to "{}")
                .say("好")
            val thread = h.newThread()
            h.start(this, threadId = thread).await()

            val points = h.store.forkablePoints(thread)
            val atSeq = points.last()

            val forked = h.session.fork(thread, atSeq, "分支")
            val newId = forked.getOrNull()
            assertThat(newId).isNotNull()

            val payloads = h.store.payloadsOf(newId!!)
            val created = payloads.filterIsInstance<ThreadCreated>().single()
            assertThat(created.parentThreadId).isEqualTo(thread)
            assertThat(created.parentSeq).isEqualTo(atSeq)
            val baseline = payloads.filterIsInstance<Baseline>().single()
            assertThat(baseline.reason).isEqualTo(BaselineReason.FORK)
            // 笔记随 Baseline(FORK) 原样保留
            assertThat(baseline.notes).containsExactly("用户名是小明")

            // live 一致：源 Thread 在 atSeq 处的渲染 == 新 Thread 的 Baseline 与 live
            val cfg = FakeConfigProvider().current()
            val renderer = HistoryRenderer(h.store, defaultRenderRules())
            val sourceEvents = h.store.events(thread, 0, atSeq)
            val idx = sourceEvents.indexOfLast { it.payload is Baseline }
            val slice = if (idx < 0) sourceEvents else sourceEvents.drop(idx)
            val expected = renderer.renderEvents(slice, cfg).map { it.msg }
            assertThat(baseline.messages).isEqualTo(expected)
            assertThat(renderer.messages(newId!!, cfg)).isEqualTo(expected)
        }

    @Test
    fun `13b 未配对 ToolCall 的点不允许 fork`() =
        runTest {
            val h = harness()
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .say("好")
            val thread = h.newThread()
            h.start(this, threadId = thread).await()

            // 带 ToolCall 的 ASSISTANT 消息不是分叉点
            val unpairedSeq =
                h.store
                    .events(thread, 0, Long.MAX_VALUE)
                    .first { event ->
                        val payload = event.payload as? AssistantMessage
                        payload?.blocks?.any { it is Block.ToolCall } == true
                    }.seq
            assertThat(h.store.forkablePoints(thread)).doesNotContain(unpairedSeq)

            val result = h.session.fork(thread, unpairedSeq, null)
            assertThat(result.getOrNull()).isNull()
            assertThat((result as Outcome.Err).error.code).isEqualTo(CommonErrorCodes.INVALID_ARGUMENT)
        }

    private fun harness(): RuntimeHarness =
        RuntimeHarness(
            tools = setOf(NoteTool(TestPromptRepository()), FakeTool()),
            fakes = HarnessFakes(rules = defaultRenderRules()),
        )
}
