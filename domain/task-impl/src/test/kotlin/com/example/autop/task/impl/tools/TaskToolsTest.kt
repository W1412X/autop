package com.example.autop.task.impl.tools

import com.example.autop.agent.RunScopedValues
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.FlowId
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.TaskId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowListItem
import com.example.autop.flow.FlowStore
import com.example.autop.flow.StoredFlow
import com.example.autop.task.ScheduleSpec
import com.example.autop.task.Task
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.TaskExecutor
import com.example.autop.task.Trigger
import com.example.autop.task.impl.testing.FakeTaskRepository
import com.example.autop.task.impl.testing.FakeThreadStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

/** 任务工具行为测试：创建 / 列表 / 更新的落库结果与快捷入口（docs/tech/07 §7）。 */
class TaskToolsTest {
    @Test
    fun `create_task saves a manual task with three quick links`() =
        runTest {
            val repo = FakeTaskRepository()
            val tool = createTool(repo)
            val args =
                buildJsonObject {
                    put("name", " 早报 ")
                    put("goal", "播报天气")
                }
            val out = tool.invoke(args, ctx())

            assertThat(out.ok).isTrue()
            val saved = repo.saved.single()
            assertThat(saved.name).isEqualTo("早报")
            assertThat(saved.goal).isEqualTo("播报天气")
            assertThat(saved.trigger).isEqualTo(Trigger.Manual)
            assertThat(saved.enabled).isTrue()
            assertThat(out.linkKinds())
                .containsExactly("task_detail", "task_edit", "task_ai")
                .inOrder()
            assertThat(out.links().all { it.getValue("taskId").jsonPrimitive.content == saved.id.value }).isTrue()
        }

    @Test
    fun `create_task rejects missing name or goal`() =
        runTest {
            val repo = FakeTaskRepository()
            val out = createTool(repo).invoke(buildJsonObject { put("name", "早报") }, ctx())

            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(TaskErrorCodes.INVALID)
            assertThat(repo.saved).isEmpty()
        }

    @Test
    fun `create_task turns a cron expression into a schedule trigger`() =
        runTest {
            val repo = FakeTaskRepository()
            val args =
                buildJsonObject {
                    put("name", "早报")
                    put("goal", "播报天气")
                    put("trigger_type", "cron")
                    put("cron", "0 8 * * *")
                }
            createTool(repo).invoke(args, ctx())

            val trigger = repo.saved.single().trigger
            assertThat(trigger).isInstanceOf(Trigger.Schedule::class.java)
            assertThat(((trigger as Trigger.Schedule).spec as ScheduleSpec.Cron).expr).isEqualTo("0 8 * * *")
        }

    @Test
    fun `create_task binds a flow resolved by name`() =
        runTest {
            val repo = FakeTaskRepository()
            val flow = storedFlow("f1", "签到")
            val tool = createTool(repo, MapFlowStore(listOf(flow)))
            val args =
                buildJsonObject {
                    put("name", "签到")
                    put("goal", "运行签到流程")
                    put("flow", "签到")
                }
            tool.invoke(args, ctx())

            val saved = repo.saved.single()
            assertThat(saved.executor).isEqualTo(TaskExecutor.FLOW)
            assertThat(saved.flow?.flowId).isEqualTo(FlowId("f1"))
        }

    @Test
    fun `list_tasks returns newest first and links only the recent five`() =
        runTest {
            val repo = FakeTaskRepository((1..6).map { storedTask("t$it", updatedAt = it.toLong()) })
            val out = ListTasksTool(repo).invoke(buildJsonObject { }, ctx())

            val ids =
                requireNotNull(out.data)
                    .jsonObject
                    .getValue("tasks")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("id")
                            .jsonPrimitive
                            .content
                    }
            assertThat(ids).containsExactly("t6", "t5", "t4", "t3", "t2", "t1").inOrder()
            assertThat(out.links()).hasSize(5)
        }

    @Test
    fun `update_task locates by name and only changes provided fields`() =
        runTest {
            val repo = FakeTaskRepository(listOf(storedTask("t1", name = "早报", goal = "旧目标", updatedAt = 1L)))
            val args =
                buildJsonObject {
                    put("task", " 早报 ")
                    put("name", "日报")
                    put("enabled", false)
                }
            val out = updateTool(repo).invoke(args, ctx())

            assertThat(out.ok).isTrue()
            val saved = repo.saved.single()
            assertThat(saved.id).isEqualTo(TaskId("t1"))
            assertThat(saved.name).isEqualTo("日报")
            assertThat(saved.goal).isEqualTo("旧目标")
            assertThat(saved.enabled).isFalse()
            assertThat(saved.trigger).isEqualTo(Trigger.Manual)
            assertThat(out.linkKinds()).containsExactly("task_detail", "task_edit", "task_ai")
        }

    @Test
    fun `update_task reports an unknown task`() =
        runTest {
            val repo = FakeTaskRepository()
            val out = updateTool(repo).invoke(buildJsonObject { put("task", "不存在") }, ctx())

            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(TaskErrorCodes.NOT_FOUND)
            assertThat(repo.saved).isEmpty()
        }

    private fun createTool(
        repo: FakeTaskRepository,
        flows: FlowStore = MapFlowStore(emptyList()),
    ): CreateTaskTool = CreateTaskTool(repo, flows, emptySet(), FakeClock(start = 1_000L), SeqIds())

    private fun updateTool(repo: FakeTaskRepository): UpdateTaskTool =
        UpdateTaskTool(repo, MapFlowStore(emptyList()), emptySet(), FakeClock(start = 1_000L))

    /** 流程 Beta 功能默认关闭（ADR-0030）；绑定流程的用例需要显式开启。 */
    private fun flowsEnabledOverride(): JsonObject =
        buildJsonObject {
            put("features", buildJsonObject { put("flows", true) })
        }

    private fun ctx(): ToolContext =
        object : ToolContext {
            override val runId: RunId = RunId("run-tools")

            override val threadId: ThreadId = ThreadId("thread-tools")

            override val config = FakeConfigProvider(flowsEnabledOverride()).current()

            override val runScope: RunScopedValues =
                object : RunScopedValues {
                    private val values = mutableMapOf<String, Any>()

                    @Suppress("UNCHECKED_CAST")
                    override fun <T : Any> getOrPut(
                        key: String,
                        create: () -> T,
                    ): T = values.getOrPut(key) { create() } as T
                }

            override val threadStore = FakeThreadStore()

            override suspend fun askUser(
                question: String,
                options: List<String>,
                timeoutMs: Long,
            ): Outcome<String> = error("unused")

            override fun emitProgress(text: String) = Unit
        }

    private class SeqIds : IdGenerator {
        private var next: Int = 0

        override fun newId(): String = "task-${++next}"
    }

    private class MapFlowStore(
        private val flows: List<StoredFlow>,
    ) : FlowStore {
        override fun observeAll(): Flow<List<FlowListItem>> = flowOf(emptyList())

        override suspend fun get(id: FlowId): StoredFlow? = flows.firstOrNull { it.id == id }

        override suspend fun resolve(nameOrId: String): StoredFlow? =
            flows.firstOrNull {
                it.id.value == nameOrId || it.definition.name.equals(nameOrId, ignoreCase = true)
            }

        override suspend fun listAll(): List<StoredFlow> = flows

        override suspend fun save(definition: FlowDefinition): Outcome<StoredFlow> = error("unused")

        override suspend fun delete(id: FlowId): Outcome<Unit> = error("unused")

        override suspend fun exists(id: FlowId): Boolean = get(id) != null
    }

    private fun storedFlow(
        id: String,
        name: String,
    ): StoredFlow =
        StoredFlow(
            id = FlowId(id),
            definition = FlowDefinition(id = id, name = name),
            createdAt = 0L,
            updatedAt = 0L,
        )

    private fun storedTask(
        id: String,
        name: String = "任务$id",
        goal: String = "目标$id",
        updatedAt: Long = 0L,
    ): Task =
        Task(
            id = TaskId(id),
            name = name,
            goal = goal,
            trigger = Trigger.Manual,
            createdAt = 0L,
            updatedAt = updatedAt,
        )
}

private fun ToolOutput.links(): List<JsonObject> {
    val data = data as? JsonObject ?: return emptyList()
    return (data["links"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
}

private fun ToolOutput.linkKinds(): List<String> = links().map { it.getValue("kind").jsonPrimitive.content }
