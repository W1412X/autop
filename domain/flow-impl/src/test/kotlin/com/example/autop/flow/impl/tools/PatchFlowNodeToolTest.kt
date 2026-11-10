/**
 * `tool.patch_flow_node` 的验收用例（docs/design/flows-precondition-selfrepair.md S2/S5）。
 *
 * 覆盖五类路径：流程不存在、节点不存在、节点不是 tool 节点、没有可用轨迹、有轨迹。
 * 自动修订开关打开时，修订直接落库并推给运行中的实例。
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
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.FlowId
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowIssue
import com.example.autop.flow.FlowRunHandle
import com.example.autop.flow.FlowRunRequest
import com.example.autop.flow.FlowSession
import com.example.autop.flow.FlowValidationService
import com.example.autop.flow.SetNode
import com.example.autop.flow.ToolNode
import com.example.autop.flow.impl.author.FlowDraftWriter
import com.example.autop.flow.impl.db.FlowsDb
import com.example.autop.flow.impl.di.FlowStorage
import com.example.autop.flow.impl.service.FlowRevisionService
import com.example.autop.flow.impl.store.RoomEndpointStore
import com.example.autop.flow.impl.store.RoomFlowRunStore
import com.example.autop.flow.impl.store.RoomFlowStateStore
import com.example.autop.flow.impl.store.RoomFlowStore
import com.example.autop.flow.impl.store.RoomFlowVersionStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class PatchFlowNodeToolTest {
    private lateinit var db: FlowsDb
    private lateinit var storage: FlowStorage
    private lateinit var flows: RoomFlowStore
    private val replaySteps = mutableListOf<ReplayableAction>()
    private var sessionPatch: FlowDefinition? = null

    private val clock =
        object : Clock {
            override fun nowMillis(): Long = 1_000L
        }

    private val logger =
        object : Logger {
            override fun d(
                tag: String,
                event: String,
                vararg kv: Pair<String, Any?>,
            ) = Unit

            override fun i(
                tag: String,
                event: String,
                vararg kv: Pair<String, Any?>,
            ) = Unit

            override fun w(
                tag: String,
                event: String,
                vararg kv: Pair<String, Any?>,
                error: Throwable?,
            ) = Unit

            override fun e(
                tag: String,
                event: String,
                vararg kv: Pair<String, Any?>,
                error: Throwable?,
            ) = Unit
        }

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
            ): List<ThreadEvent> =
                replaySteps.map { step ->
                    ThreadEvent(
                        threadId = threadId,
                        seq = 1,
                        runId = RUN_ID,
                        createdAt = 0,
                        payload =
                            ToolResultEvent(
                                callId = "c1",
                                toolId = step.toolId,
                                ok = true,
                                blocks = emptyList(),
                                durationMs = 0,
                                replay = step,
                            ),
                    )
                }

            override suspend fun eventsSinceLatestBaseline(threadId: ThreadId): List<ThreadEvent> = emptyList()

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

            override suspend fun titleOf(threadId: ThreadId): String? = null

            override suspend fun delete(threadId: ThreadId) = Unit

            override suspend fun forkablePoints(threadId: ThreadId): List<Long> = emptyList()
        }

    private val validation =
        object : FlowValidationService {
            override suspend fun validate(definition: FlowDefinition): List<FlowIssue> = emptyList()

            override suspend fun needsScreen(definition: FlowDefinition): Boolean = false
        }

    private val ids =
        object : IdGenerator {
            private var counter = 0

            override fun newId(): String {
                counter++
                return "draft-$counter"
            }
        }

    private val session =
        object : FlowSession {
            override fun start(
                request: FlowRunRequest,
                scope: CoroutineScope,
            ): FlowRunHandle = error("unused")

            override suspend fun applyPatch(
                flowId: String,
                definition: FlowDefinition,
            ): Outcome<Unit> {
                sessionPatch = definition
                return Outcome.Ok(Unit)
            }
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
        flows = RoomFlowStore(db, runs, clock) { false }
        val state = RoomFlowStateStore(db, clock)
        val endpoints = RoomEndpointStore(db, FakeSecretStore())
        val versions = RoomFlowVersionStore(db, clock, flows)
        storage = FlowStorage(db, runs, flows, state, endpoints, versions)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun tool(autoPatchEnabled: Boolean = false): PatchFlowNodeTool {
        val overrides =
            if (autoPatchEnabled) {
                JsonObject(
                    mapOf(
                        "flow" to
                            JsonObject(
                                mapOf("recovery" to JsonObject(mapOf("autoPatch" to JsonPrimitive(true)))),
                            ),
                    ),
                )
            } else {
                JsonObject(emptyMap())
            }
        val revisions = FlowRevisionService(flows, { session }, logger)
        return PatchFlowNodeTool(
            threads,
            flows,
            FlowDraftWriter(storage, validation, ids, clock),
            revisions,
            FakeConfigProvider(overrides),
            logger,
        )
    }

    private suspend fun saveFlow(name: String = "发布流程") {
        flows.save(
            FlowDefinition(
                id = "f1",
                name = name,
                steps = listOf(ToolNode(id = "n1", tool = "tap")),
            ),
        )
    }

    private fun tapStep(text: String) =
        ReplayableAction(
            toolId = "tool.tap",
            replayArgs =
                JsonObject(
                    mapOf(
                        "x" to JsonPrimitive(10),
                        "y" to JsonPrimitive(20),
                        "_hit" to
                            JsonObject(
                                mapOf(
                                    "hit" to JsonObject(mapOf("text" to JsonPrimitive(text))),
                                ),
                            ),
                    ),
                ),
            pre = ReplayStateSample(null, null, "fp", emptyList(), observeMode = "a11y"),
            postFingerprint = null,
            execution = "node_action",
        )

    private fun args(
        flow: String = "发布流程",
        nodeId: String = "n1",
    ) = JsonObject(
        mapOf(
            "flow" to JsonPrimitive(flow),
            "node_id" to JsonPrimitive(nodeId),
            "reason" to JsonPrimitive("按钮改名了"),
        ),
    )

    @Test
    fun `unknown flow fails`() {
        runBlocking {
            val out = tool().invoke(args(flow = "不存在"), ctx())
            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(FlowErrorCodes.NOT_FOUND)
        }
    }

    @Test
    fun `unknown node fails`() {
        runBlocking {
            saveFlow()
            val out = tool().invoke(args(nodeId = "nope"), ctx())
            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(FlowErrorCodes.NOT_FOUND)
        }
    }

    @Test
    fun `non tool node fails`() {
        runBlocking {
            flows.save(
                FlowDefinition(
                    id = "f1",
                    name = "发布流程",
                    steps = listOf(SetNode(id = "s1", vars = mapOf("a" to "1"))),
                ),
            )
            val out = tool().invoke(args(nodeId = "s1"), ctx())
            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(FlowErrorCodes.NOT_FOUND)
        }
    }

    @Test
    fun `no replayable action fails`() {
        runBlocking {
            saveFlow()
            val out = tool().invoke(args(), ctx())
            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(FlowErrorCodes.INVALID)
        }
    }

    @Test
    fun `a draft is created from the last successful action`() {
        runBlocking {
            saveFlow()
            replaySteps += tapStep("第一步")
            replaySteps += tapStep("发布")
            val out = tool().invoke(args(), ctx())
            assertThat(out.ok).isTrue()
            val draft =
                storage.db
                    .flowDao()
                    .drafts()
                    .single()
            val definition = AutopJson.default.decodeFromString(FlowDefinition.serializer(), draft.draftJson!!)
            val node = definition.steps.single() as ToolNode
            assertThat(node.target?.value).isEqualTo("发布")
            assertThat(node.id).isEqualTo("n1")
        }
    }

    @Test
    fun `auto patch writes the revision and pushes it to the running flow`() {
        runBlocking {
            saveFlow()
            replaySteps += tapStep("发布")
            val out = tool(autoPatchEnabled = true).invoke(args(), ctx())
            assertThat(out.ok).isTrue()
            val stored = flows.get(FlowId("f1"))
            assertThat((stored?.definition?.steps?.single() as ToolNode).target?.value).isEqualTo("发布")
            assertThat(sessionPatch).isNotNull()
            // 旧定义进了版本库。
            assertThat(storage.versions.list("f1")).isNotEmpty()
        }
    }

    private fun ctx() =
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

            override val threadStore: ThreadStore = threads

            override suspend fun askUser(
                question: String,
                options: List<String>,
                timeoutMs: Long,
            ): Outcome<String> = error("unused")

            override fun emitProgress(text: String) = Unit
        }

    private companion object {
        val RUN_ID: RunId = RunId("r1")
    }
}

/** 测试用密钥存储。 */
private class FakeSecretStore : com.example.autop.core.common.SecretStore {
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
