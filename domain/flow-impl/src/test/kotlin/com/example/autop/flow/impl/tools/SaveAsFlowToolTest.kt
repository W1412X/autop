/**
 * `tool.save_as_flow` 统一创作入口的验收用例（docs/design/flows-contracts-evidence §4.1）：
 * 证据 + 骨架进创作循环产出草稿；分析失败时保底直接把骨架存为草稿。
 */
package com.example.autop.flow.impl.tools

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.agent.EventPayload
import com.example.autop.agent.ReplayStateSample
import com.example.autop.agent.ReplayableAction
import com.example.autop.agent.RunScopedValues
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolResultEvent
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Clock
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.flow.DraftInfo
import com.example.autop.flow.FlowAnalysisRequest
import com.example.autop.flow.FlowAuthor
import com.example.autop.flow.FlowAuthorSession
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowIssue
import com.example.autop.flow.FlowValidationService
import com.example.autop.flow.impl.author.FlowDraftWriter
import com.example.autop.flow.impl.db.FlowsDb
import com.example.autop.flow.impl.di.FlowStorage
import com.example.autop.flow.impl.store.RoomEndpointStore
import com.example.autop.flow.impl.store.RoomFlowRunStore
import com.example.autop.flow.impl.store.RoomFlowStateStore
import com.example.autop.flow.impl.store.RoomFlowStore
import com.example.autop.flow.impl.store.RoomFlowVersionStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class SaveAsFlowToolTest {
    private lateinit var db: FlowsDb
    private lateinit var storage: FlowStorage
    private var analyzeResult: Outcome<String> = Outcome.Ok("d-analyzed")
    private var analyzeRequest: FlowAnalysisRequest? = null

    private val clock =
        object : Clock {
            override fun nowMillis(): Long = 1_000L
        }

    private val author =
        object : FlowAuthor {
            override suspend fun open(
                draftId: String?,
                editFlowId: String?,
            ): FlowAuthorSession = error("unused")

            override suspend fun drafts(): List<DraftInfo> = emptyList()

            override suspend fun deleteDraft(draftId: String) = Unit

            override suspend fun analyze(request: FlowAnalysisRequest): Outcome<String> {
                analyzeRequest = request
                return analyzeResult
            }
        }

    private val validation =
        object : FlowValidationService {
            override suspend fun validate(definition: FlowDefinition): List<FlowIssue> = emptyList()

            override suspend fun needsScreen(definition: FlowDefinition): Boolean = false
        }

    private val ids =
        object : IdGenerator {
            private var counter = 0

            override fun newId(): String = "f${++counter}"
        }

    private val events =
        listOf(
            ThreadEvent(
                threadId = ThreadId("t1"),
                seq = 1,
                runId = RUN_ID,
                createdAt = 0,
                payload =
                    ToolResultEvent(
                        callId = "c1",
                        toolId = "tool.tap",
                        ok = true,
                        blocks = emptyList(),
                        replay =
                            ReplayableAction(
                                toolId = "tool.tap",
                                replayArgs =
                                    buildJsonObject {
                                        put("x", JsonPrimitive(10))
                                        put("y", JsonPrimitive(20))
                                    },
                                pre = ReplayStateSample(null, null, "fp", emptyList(), observeMode = "a11y"),
                                postFingerprint = null,
                                execution = "test",
                            ),
                        durationMs = 1,
                    ),
            ),
        )

    private val threads =
        object : ThreadStore {
            override suspend fun createThread(
                title: String?,
                parent: Pair<ThreadId, Long>?,
            ): ThreadId = error("unused")

            override suspend fun append(
                threadId: ThreadId,
                runId: RunId?,
                payload: EventPayload,
            ): ThreadEvent = error("unused")

            override suspend fun events(
                threadId: ThreadId,
                fromSeq: Long,
                toSeq: Long,
            ): List<ThreadEvent> = events

            override suspend fun eventsSinceLatestBaseline(threadId: ThreadId): List<ThreadEvent> = events

            override fun observe(threadId: ThreadId): Flow<ThreadEvent> = flowOf()

            override fun observeChanges(): Flow<Unit> = flowOf()

            override suspend fun saveBlob(
                threadId: ThreadId,
                name: String,
                bytes: ByteArray,
            ): String = error("unused")

            override suspend fun listThreads(
                offset: Int,
                limit: Int,
            ): List<com.example.autop.agent.ThreadSummary> = emptyList()

            override suspend fun rename(
                threadId: ThreadId,
                title: String,
            ) = Unit

            override suspend fun modelProfileOf(threadId: ThreadId): String? = null

            override suspend fun setModelProfile(
                threadId: ThreadId,
                profileId: String?,
            ) = Unit

            override suspend fun titleOf(threadId: ThreadId): String? = null

            override suspend fun delete(threadId: ThreadId) = Unit

            override suspend fun forkablePoints(threadId: ThreadId): List<Long> = emptyList()
        }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, FlowsDb::class.java)
                .allowMainThreadQueries()
                .build()
        val runs = RoomFlowRunStore(db, context.filesDir, FakeConfigProvider())
        val flows = RoomFlowStore(db, runs, clock) { false }
        val state = RoomFlowStateStore(db, clock)
        val endpoints = RoomEndpointStore(db, SaveAsFlowFakeSecrets())
        val versions = RoomFlowVersionStore(db, clock, flows)
        storage = FlowStorage(db, runs, flows, state, endpoints, versions)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun tool(): SaveAsFlowTool =
        SaveAsFlowTool(threads, FlowDraftWriter(storage, validation, ids, clock), author, ids, FakeConfigProvider())

    @Test
    fun `analyzed draft id is returned with evidence attached`() {
        runBlocking {
            val out = tool().invoke(args(), ctx())

            assertThat(out.ok).isTrue()
            assertThat(analyzeRequest).isNotNull()
            assertThat(analyzeRequest!!.skeleton.steps).isNotEmpty()
            val data = out.data!!.jsonObject
            assertThat(data.getValue("draftId").jsonPrimitive.content).isEqualTo("d-analyzed")
            assertThat(data.containsKey("evidence")).isTrue()
            assertThat(storage.db.flowDao().drafts()).isEmpty()
        }
    }

    @Test
    fun `analysis failure falls back to saving the skeleton as the draft`() {
        runBlocking {
            analyzeResult = Outcome.Err(AppError(code = "flow.llm_unavailable", message = "llm down"))
            val out = tool().invoke(args(), ctx())

            assertThat(out.ok).isTrue()
            val drafts = storage.db.flowDao().drafts()
            assertThat(drafts).hasSize(1)
            val draftId =
                out.data!!
                    .jsonObject
                    .getValue("draftId")
                    .jsonPrimitive
                    .content
            assertThat(draftId).isEqualTo(drafts.single().draftId)
        }
    }

    private fun args() = buildJsonObject { put("name", "工具自测流程B") }

    private fun ctx(): ToolContext =
        object : ToolContext {
            override val runId: RunId = RUN_ID

            override val threadId: ThreadId = ThreadId("t1")

            override val config = FakeConfigProvider().current()

            override val runScope: RunScopedValues =
                object : RunScopedValues {
                    private val values = mutableMapOf<String, Any>()

                    @Suppress("UNCHECKED_CAST")
                    override fun <T : Any> getOrPut(
                        key: String,
                        create: () -> T,
                    ): T = values.getOrPut(key) { create() } as T
                }

            override val threadStore: ThreadStore
                get() = error("unused")

            override suspend fun askUser(
                question: String,
                options: List<String>,
                timeoutMs: Long,
            ): Outcome<String> = error("unused")

            override fun emitProgress(text: String) = Unit
        }

    private companion object {
        val RUN_ID: RunId = RunId("r-save")
    }
}

/** 测试用密钥存储（与 PatchFlowNodeToolTest 的同名替身区分）。 */
private class SaveAsFlowFakeSecrets : com.example.autop.core.common.SecretStore {
    private val values = mutableMapOf<String, String>()

    override suspend fun put(
        ref: String,
        secret: String,
    ) {
        values[ref] = secret
    }

    override suspend fun get(ref: String): String? = values[ref]

    override suspend fun remove(ref: String) {
        values.remove(ref)
    }
}
