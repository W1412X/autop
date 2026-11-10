/**
 * 运行状态消息的渲染（agent-harness-overhaul WP3.10）：一条 USER 消息进入 live。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.RuntimeStateReported
import com.example.autop.agent.runtime.testing.InMemoryThreadStore
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.example.autop.llm.Role
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HistoryRendererTest {
    @Test
    fun `runtime state renders as user message`() =
        runTest {
            val store = InMemoryThreadStore(FakeClock(1_000L))
            val thread = store.createThread(null, null)
            store.append(
                thread,
                null,
                RuntimeStateReported(
                    entries = mapOf("s1/k" to "行"),
                    volatileKeys = emptySet(),
                    full = true,
                    text = "[运行状态] 行",
                ),
            )
            val renderer = HistoryRenderer(store, emptySet())

            val items = renderer.live(thread, FakeConfigProvider().current())

            assertThat(items).hasSize(1)
            val msg = items.single().msg
            assertThat(msg.role).isEqualTo(Role.USER)
            assertThat((msg.blocks.single() as Block.Text).text).isEqualTo("[运行状态] 行")
        }
}
