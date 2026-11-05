/**
 * 运行期内部件（docs/tech/04 §2.3/§2.6/§3.2）：RunScopeValues 键稳定惰性单例、
 * RuntimeToolContext 的 ask/progress 委托、noUserInteraction 默认回答、
 * ObservedThreadStore 提交后回调与透传、取消收口的 ToolCall 配对补齐。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.RunRequest
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.runtime.testing.InMemoryThreadStore
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.example.autop.llm.StopReason
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RuntimeInternalsTest {
    @Test
    fun `RunScopeValues 同键只创建一次`() {
        val values = RunScopeValues()
        var created = 0

        val first =
            values.getOrPut("k") {
                created += 1
                mutableListOf("x")
            }
        val second =
            values.getOrPut("k") {
                created += 1
                mutableListOf("y")
            }

        assertThat(created).isEqualTo(1)
        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun `RuntimeToolContext 委托 ask 与 progress 并暴露请求字段`() =
        runTest {
            var asked: Triple<String, List<String>, Long>? = null
            val progressed = mutableListOf<String>()
            val config = FakeConfigProvider().current()
            val req =
                RunRequest(
                    runId = RunId("run-ctx"),
                    threadId = ThreadId("t1"),
                    input = emptyList(),
                    goal = null,
                    vars = emptyMap(),
                    config = config,
                    toolFilter = null,
                )
            val ctx =
                RuntimeToolContext(req, InMemoryThreadStore(), { question, options, timeoutMs ->
                    asked = Triple(question, options, timeoutMs)
                    Outcome.Ok("好")
                }, { progressed += it })

            val answer = ctx.askUser("继续吗？", listOf("是"), 1000)
            ctx.emitProgress("正在操作")

            assertThat((answer as Outcome.Ok).value).isEqualTo("好")
            assertThat(asked).isEqualTo(Triple("继续吗？", listOf("是"), 1000L))
            assertThat(progressed).containsExactly("正在操作")
            assertThat(ctx.runId).isEqualTo(RunId("run-ctx"))
            assertThat(ctx.threadId).isEqualTo(ThreadId("t1"))
            assertThat(ctx.config).isSameInstanceAs(config)
        }

    @Test
    fun `noUserInteraction 按超时处理并给出提示`() {
        val err = (noUserInteraction() as Outcome.Err).error

        assertThat(err.code).isEqualTo(AgentErrorCodes.ASK_TIMEOUT)
        assertThat(err.hint).isEqualTo("按默认继续或结束")
    }

    @Test
    fun `ObservedThreadStore 提交后回调并透传各操作`() =
        runTest {
            val delegate = InMemoryThreadStore()
            val seen = mutableListOf<ThreadEvent>()
            val store = ObservedThreadStore(delegate) { seen += it }
            val thread = store.createThread("标题", null)

            val event =
                store.append(
                    thread,
                    null,
                    UserMessage(blocks = listOf(Block.Text("你好")), kind = UserMessageKind.INPUT),
                )

            assertThat(seen).containsExactly(event)
            assertThat(store.events(thread, 0, Long.MAX_VALUE)).hasSize(1)
            assertThat(store.eventsSinceLatestBaseline(thread)).hasSize(1)
            store.saveBlob(thread, "b", byteArrayOf(1))
            assertThat(store.listThreads(0, 10)).hasSize(1)
            store.rename(thread, "新标题")
            assertThat(store.forkablePoints(thread)).isEmpty()
            assertThat(store.observe(thread)).isNotNull()
            store.delete(thread)
            assertThat(store.listThreads(0, 10)).isEmpty()
        }

    @Test
    fun `pairCancelledCalls 为未配对 ToolCall 补取消结果`() =
        runTest {
            val store = InMemoryThreadStore()
            val thread = store.createThread(null, null)
            val controller = controller(store, thread, this)
            val assistant =
                AssistantMessage(
                    blocks = listOf(Block.ToolCall("c1", "tap", "{}"), Block.ToolCall("c2", "swipe", "{}")),
                    usage = null,
                    stopReason = StopReason.TOOL_USE,
                    modelProfileId = "test",
                )
            controller.append(assistant)
            controller.append(toolResult("c1"))

            controller.pairCancelledCalls("已取消") { name -> "tool.$name" }

            val results = store.payloadsOf(thread).filterIsInstance<ToolResultEvent>()
            assertThat(results).hasSize(2)
            val cancelled = results.last()
            assertThat(cancelled.callId).isEqualTo("c2")
            assertThat(cancelled.toolId).isEqualTo("tool.swipe")
            assertThat(cancelled.ok).isFalse()
            assertThat((cancelled.blocks[0] as Block.Text).text).isEqualTo("已取消")
        }

    @Test
    fun `pairCancelledCalls 无助手消息时不补`() =
        runTest {
            val store = InMemoryThreadStore()
            val thread = store.createThread(null, null)
            val controller = controller(store, thread, this)

            controller.pairCancelledCalls("已取消") { name -> "tool.$name" }

            assertThat(store.payloadsOf(thread)).isEmpty()
        }

    private fun controller(
        store: InMemoryThreadStore,
        thread: ThreadId,
        scope: CoroutineScope,
    ): RunController =
        RunController(
            req =
                RunRequest(
                    runId = RunId("run-cancel"),
                    threadId = thread,
                    input = emptyList(),
                    goal = null,
                    vars = emptyMap(),
                    config = FakeConfigProvider().current(),
                    toolFilter = null,
                ),
            store = store,
            scope = scope,
            eventReplay = 0,
            prompts = TestPromptRepository(),
        )

    private fun toolResult(callId: String): ToolResultEvent =
        ToolResultEvent(
            callId = callId,
            toolId = "tool.tap",
            ok = true,
            blocks = listOf(Block.Text("ok")),
            durationMs = 1,
        )
}
