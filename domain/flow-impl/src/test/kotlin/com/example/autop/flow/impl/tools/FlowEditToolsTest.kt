/**
 * `tool.create_flow` / `tool.edit_flow` 的行为用例：按参数建流程、改名称/步骤、
 * 修订留版本；非法输入给出可诊断错误（docs/design/flows.md §14）。
 */
package com.example.autop.flow.impl.tools

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.agent.RunScopedValues
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolContext
import com.example.autop.core.common.Clock
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
import com.example.autop.flow.ToolNode
import com.example.autop.flow.impl.db.FlowsDb
import com.example.autop.flow.impl.service.FlowRevisionService
import com.example.autop.flow.impl.store.RoomFlowRunStore
import com.example.autop.flow.impl.store.RoomFlowStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
internal class FlowEditToolsTest {
    private lateinit var db: FlowsDb
    private lateinit var flows: RoomFlowStore
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
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createTool(): CreateFlowTool = CreateFlowTool(flows, validation, ids)

    private fun editTool(): EditFlowTool {
        val revisions = FlowRevisionService(flows, { session }, logger)
        return EditFlowTool(flows, validation, revisions)
    }

    @Test
    fun `create_flow builds tool nodes and saves the flow`() =
        runBlocking {
            val args =
                buildJsonObject {
                    put("name", "签到")
                    put("description", "每天打开签到页点签到")
                    put(
                        "steps",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("tool", "tap")
                                    put("target", buildJsonObject { put("value", "签到") })
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("tool", "input_text")
                                    put("args", buildJsonObject { put("text", "你好") })
                                },
                            )
                        },
                    )
                }
            val out = createTool().invoke(args, ctx())

            assertThat(out.ok).isTrue()
            val stored = flows.resolve("签到")!!
            val nodes = stored.definition.steps.map { it as ToolNode }
            assertThat(nodes.map { it.tool }).containsExactly("tap", "input_text").inOrder()
            assertThat(nodes.map { it.id }).containsExactly("n1", "n2").inOrder()
            assertThat(nodes[0].target?.value).isEqualTo("签到")
            assertThat(nodes[1].args["text"]?.jsonPrimitive?.content).isEqualTo("你好")
            val kinds =
                out.data!!
                    .jsonObject
                    .getValue("links")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("kind")
                            .jsonPrimitive
                            .content
                    }
            assertThat(kinds).contains("flow_detail")
            assertThat(kinds).contains("flow_edit")
            assertThat(kinds).contains("flow_ai")
        }

    @Test
    fun `create_flow rejects missing name and empty steps`() =
        runBlocking {
            val noName =
                createTool().invoke(
                    buildJsonObject {
                        put("steps", buildJsonArray { add(buildJsonObject { put("tool", "wait") }) })
                    },
                    ctx(),
                )
            assertThat(noName.ok).isFalse()
            assertThat(noName.error?.code).isEqualTo(FlowErrorCodes.ARGS_INVALID)
            val noSteps =
                createTool().invoke(
                    buildJsonObject {
                        put("name", "空流程")
                        put("steps", buildJsonArray { })
                    },
                    ctx(),
                )
            assertThat(noSteps.ok).isFalse()
            assertThat(noSteps.error?.code).isEqualTo(FlowErrorCodes.ARGS_INVALID)
        }

    @Test
    fun `create_flow rejects a broken target`() =
        runBlocking {
            val args =
                buildJsonObject {
                    put("name", "坏目标")
                    put(
                        "steps",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("tool", "tap")
                                    put("target", "oops")
                                },
                            )
                        },
                    )
                }
            val out = createTool().invoke(args, ctx())

            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(FlowErrorCodes.INVALID)
        }

    @Test
    fun `edit_flow patches adds and removes steps`() =
        runBlocking {
            saveSeed()
            val args =
                buildJsonObject {
                    put("flow", "发布流程")
                    put("name", "发布流程V2")
                    put(
                        "set_step",
                        buildJsonObject {
                            put("node_id", "n1")
                            put("target", buildJsonObject { put("value", "确定") })
                        },
                    )
                    put(
                        "add_step",
                        buildJsonObject {
                            put("tool", "wait")
                            put("args", buildJsonObject { put("ms", 100) })
                            put("after", "n1")
                        },
                    )
                    put("remove_step", "n2")
                }
            val out = editTool().invoke(args, ctx())

            assertThat(out.ok).isTrue()
            val stored = flows.resolve("发布流程V2")!!
            val nodes = stored.definition.steps.map { it as ToolNode }
            assertThat(nodes.map { it.tool }).containsExactly("tap", "wait").inOrder()
            assertThat(nodes.map { it.id }).containsExactly("n1", "n3").inOrder()
            assertThat(nodes[0].target?.value).isEqualTo("确定")
            assertThat(sessionPatch).isNotNull()
        }

    @Test
    fun `edit_flow reports unknown flow and missing changes`() =
        runBlocking {
            val missing = editTool().invoke(buildJsonObject { put("flow", "不存在") }, ctx())
            assertThat(missing.ok).isFalse()
            assertThat(missing.error?.code).isEqualTo(FlowErrorCodes.NOT_FOUND)
            saveSeed()
            val empty = editTool().invoke(buildJsonObject { put("flow", "发布流程") }, ctx())
            assertThat(empty.ok).isFalse()
            assertThat(empty.error?.code).isEqualTo(FlowErrorCodes.ARGS_INVALID)
        }

    private suspend fun saveSeed() {
        flows.save(
            FlowDefinition(
                id = "f1",
                name = "发布流程",
                steps =
                    listOf(
                        ToolNode(id = "n1", tool = "tap"),
                        ToolNode(id = "n2", tool = "input_text"),
                    ),
            ),
        )
    }

    private fun ctx(): ToolContext =
        object : ToolContext {
            override val runId: RunId = RunId("run-edit")

            override val threadId: ThreadId = ThreadId("t-edit")

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
}
