/**
 * [TaskAuthorTools] 回归测试：真实工具经 [ToolInvoker] 调用时必须传**模型可见名**
 * （`observe`/`memory_search`/`list_flows`）。历史上误传工具 id（`tool.memory_search`），
 * 每次调用都命中 `agent.unknown_tool`——检索记忆/列流程从未成功过。
 */
package com.example.autop.task.impl.author

import com.example.autop.agent.EventPayload
import com.example.autop.agent.RunRequest
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ThreadSummary
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolInvoker
import com.example.autop.agent.ToolInvokerFactory
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.llm.Block
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class TaskAuthorToolsTest {
    @Test
    fun `memory_search invokes the real tool by visible name`() =
        runTest {
            val f = fixture()
            val out = f.tools.run("memory_search", buildJsonObject { put("query", "张三") }) {}
            assertThat(out).doesNotContain("error")
            val (name, args) = f.invoker.calls.single()
            assertThat(name).isEqualTo("memory_search")
            assertThat(args["query"].toString()).contains("张三")
        }

    @Test
    fun `inspect_screen invokes observe with the requested mode`() =
        runTest {
            val f = fixture()
            f.tools.run("inspect_screen", buildJsonObject { put("mode", "visual") }) {}
            val (name, args) = f.invoker.calls.single()
            assertThat(name).isEqualTo("observe")
            assertThat(args["mode"].toString()).contains("visual")
        }

    @Test
    fun `list_flows invokes the real tool by visible name`() =
        runTest {
            val f = fixture()
            f.tools.run("list_flows", buildJsonObject { }) {}
            assertThat(f.invoker.calls.map { it.first }).containsExactly("list_flows")
        }

    @Test
    fun `invoker tool filter keeps read-only tool ids`() =
        runTest {
            val f = fixture()
            f.tools.run("list_flows", buildJsonObject { }) {}
            assertThat(f.factory.filter)
                .containsExactly("tool.observe", "tool.memory_search", "tool.list_flows")
        }

    @Test
    fun `propose assembles draft fields`() {
        val f = fixture()
        val draft =
            f.tools.propose(
                buildJsonObject {
                    put("name", "签到")
                    put("goal", "每天 18 点运行签到流程")
                    put("triggerType", "cron")
                    put("cron", "0 18 * * *")
                    put("executorFlow", "签到")
                },
            )
        assertThat(draft.name).isEqualTo("签到")
        assertThat(draft.triggerType).isEqualTo("cron")
        assertThat(draft.cron).isEqualTo("0 18 * * *")
        assertThat(draft.executorFlow).isEqualTo("签到")
        assertThat(draft.needsScreen).isTrue()
    }

    private fun fixture(): Fixture {
        val invoker = RecordingInvoker()
        val factory = RecordingFactory(invoker)
        val tools =
            TaskAuthorTools(
                invokerFactory = { factory },
                configProvider = FakeConfigProvider(),
                prompts = FakePromptRepository(),
                ids = FixedIds,
                threadStore = UnusedThreadStore,
            )
        return Fixture(tools, invoker, factory)
    }

    private class Fixture(
        val tools: TaskAuthorTools,
        val invoker: RecordingInvoker,
        val factory: RecordingFactory,
    )

    private object FixedIds : IdGenerator {
        override fun newId(): String = "fixed-id"
    }

    private class RecordingInvoker : ToolInvoker {
        val calls: MutableList<Pair<String, JsonObject>> = mutableListOf()

        override suspend fun invoke(
            toolName: String,
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            calls += toolName to args
            return ToolOutput(ok = true, content = listOf(Block.Text("ok")))
        }
    }

    private class RecordingFactory(
        val invoker: RecordingInvoker,
    ) : ToolInvokerFactory {
        var filter: Set<String>? = null

        override fun create(
            request: RunRequest,
            toolFilter: Set<String>?,
        ): ToolInvoker {
            filter = toolFilter
            return invoker
        }
    }

    /** 本测试不触达线程存储，未用方法直接抛未实现。 */
    private object UnusedThreadStore : ThreadStore {
        override suspend fun createThread(
            title: String?,
            parent: Pair<ThreadId, Long>?,
        ): ThreadId = TODO()

        override suspend fun append(
            threadId: ThreadId,
            runId: RunId?,
            payload: EventPayload,
        ): ThreadEvent = TODO()

        override suspend fun events(
            threadId: ThreadId,
            fromSeq: Long,
            toSeq: Long,
        ): List<ThreadEvent> = TODO()

        override suspend fun eventsSinceLatestBaseline(threadId: ThreadId): List<ThreadEvent> = TODO()

        override fun observe(threadId: ThreadId): Flow<ThreadEvent> = emptyFlow()

        override fun observeChanges(): Flow<Unit> = emptyFlow()

        override suspend fun saveBlob(
            threadId: ThreadId,
            name: String,
            bytes: ByteArray,
        ): String = TODO()

        override suspend fun listThreads(
            offset: Int,
            limit: Int,
        ): List<ThreadSummary> = TODO()

        override suspend fun rename(
            threadId: ThreadId,
            title: String,
        ) = TODO()

        override suspend fun modelProfileOf(threadId: ThreadId): String? = null

        override suspend fun setModelProfile(
            threadId: ThreadId,
            profileId: String?,
        ) = Unit

        override suspend fun titleOf(threadId: ThreadId): String? = null

        override suspend fun delete(threadId: ThreadId) = TODO()

        override suspend fun forkablePoints(threadId: ThreadId): List<Long> = emptyList()
    }
}
