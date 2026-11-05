/**
 * 备份导出/导入往返测试（docs/tech/08 §4.5 导出/导入，WP11 验收）：
 * 导出 → 清空 → 导入后记忆、任务、模型配置恢复；`format`/`version` 不合法返回 `backup.invalid`；
 * 记忆按深度先父后子写回；导出与导入都不带密钥（`apiKeyRef` 置空）。
 */
package com.example.autop.feature.settings.me.backup

import com.example.autop.core.common.AppError
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.TaskId
import com.example.autop.core.common.getOrNull
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeModelProfileStore
import com.example.autop.feature.settings.me.testProfile
import com.example.autop.llm.ModelRoles
import com.example.autop.memory.AppliedMode
import com.example.autop.memory.MemoryNode
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryStore
import com.example.autop.memory.MemoryVersion
import com.example.autop.memory.MemoryWrite
import com.example.autop.memory.WriteMode
import com.example.autop.memory.WriteResult
import com.example.autop.task.RunRecord
import com.example.autop.task.Task
import com.example.autop.task.TaskRepository
import com.example.autop.task.Trigger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BackupRoundTripTest {
    private val clock = FakeClock(start = 42L)

    @Test
    fun `export then import restores tasks and profiles without secrets`() =
        runTest {
            val memory = FakeMemoryStore()
            memory.seed(node("/people"), node("/people/张三"))
            val tasks = FakeTaskRepository(listOf(testTask("t1")))
            val profiles = FakeModelProfileStore(listOf(testProfile("p1")), ModelRoles(main = "p1"))
            val bundle = BackupExporter(memory, tasks, profiles, clock).export()

            assertThat(bundle.format).isEqualTo(BackupFormat.FORMAT)
            assertThat(bundle.version).isEqualTo(BackupFormat.VERSION)
            assertThat(bundle.exportedAt).isEqualTo(42L)
            assertThat(bundle.modelProfiles.single().apiKeyRef).isEmpty()

            val json = encode(bundle)
            val emptyMemory = FakeMemoryStore()
            val emptyTasks = FakeTaskRepository(emptyList())
            val emptyProfiles = FakeModelProfileStore()
            val result = BackupImporter(emptyMemory, emptyTasks, emptyProfiles).import(json).getOrNull()!!

            assertThat(result.memories).isEqualTo(2)
            assertThat(result.tasks).isEqualTo(1)
            assertThat(result.modelProfiles).isEqualTo(1)
            assertThat(emptyTasks.tasks.single().name).isEqualTo("任务 t1")
            assertThat(
                emptyProfiles.profiles
                    .first()
                    .single()
                    .apiKeyRef,
            ).isEmpty()
            assertThat(emptyProfiles.roles.first().main).isEqualTo("p1")
            assertThat(emptyMemory.nodes.keys).containsExactly("/people", "/people/张三")
        }

    @Test
    fun `import rejects unknown format`() =
        runTest {
            val bundle = emptyBundle().copy(format = "other")
            val result =
                BackupImporter(FakeMemoryStore(), FakeTaskRepository(emptyList()), FakeModelProfileStore())
                    .import(encode(bundle))
            assertThat((result as Outcome.Err).error.code).isEqualTo("backup.invalid")
        }

    @Test
    fun `import rejects newer version`() =
        runTest {
            val bundle = emptyBundle().copy(version = BackupFormat.VERSION + 1)
            val result =
                BackupImporter(FakeMemoryStore(), FakeTaskRepository(emptyList()), FakeModelProfileStore())
                    .import(encode(bundle))
            assertThat((result as Outcome.Err).error.code).isEqualTo("backup.invalid")
        }

    @Test
    fun `import rejects malformed json`() =
        runTest {
            val result =
                BackupImporter(FakeMemoryStore(), FakeTaskRepository(emptyList()), FakeModelProfileStore())
                    .import("{not json")
            assertThat((result as Outcome.Err).error.code).isEqualTo("backup.invalid")
        }

    @Test
    fun `import writes memories parents before children`() =
        runTest {
            val memory = FakeMemoryStore()
            val bundle =
                emptyBundle().copy(memories = listOf(node("/people/张三"), node("/people")))
            val result =
                BackupImporter(memory, FakeTaskRepository(emptyList()), FakeModelProfileStore())
                    .import(encode(bundle))
                    .getOrNull()!!
            assertThat(result.memories).isEqualTo(2)
            assertThat(memory.writes.map { it.path.value })
                .containsExactly("/people", "/people/张三")
                .inOrder()
        }

    @Test
    fun `import skips invalid tasks`() =
        runTest {
            val tasks = FakeTaskRepository(listOf(testTask("t1", name = "")))
            val bundle = emptyBundle().copy(tasks = listOf(testTask("t1", name = "")))
            val result =
                BackupImporter(FakeMemoryStore(), tasks, FakeModelProfileStore())
                    .import(encode(bundle))
                    .getOrNull()!!
            assertThat(result.tasks).isEqualTo(0)
        }

    private fun emptyBundle(): BackupFile =
        BackupFile(
            format = BackupFormat.FORMAT,
            version = BackupFormat.VERSION,
            exportedAt = 0L,
            memories = emptyList(),
            tasks = emptyList(),
            modelProfiles = emptyList(),
        )

    private fun encode(bundle: BackupFile): String =
        com.example.autop.core.common.AutopJson.default
            .encodeToString(BackupFile.serializer(), bundle)
}

private fun node(path: String): MemoryNode =
    MemoryNode(
        path = MemoryPath.parse(path).getOrNull()!!,
        title = path.substringAfterLast('/'),
        summary = "摘要",
        content = "正文",
        tags = emptyList(),
        links = emptyList(),
        source = MemorySource.USER,
        version = 1,
        createdAt = 1L,
        updatedAt = 2L,
        childCount = 0,
    )

private fun testTask(
    id: String,
    name: String = "任务 $id",
): Task =
    Task(
        id = TaskId(id),
        name = name,
        goal = "目标 $id",
        trigger = Trigger.Manual,
        createdAt = 1L,
        updatedAt = 2L,
    )

/** 内存版 [MemoryStore]：`children` 按父路径直取子节点，`write` 记录请求（UPDATE 缺失时报错供回退 CREATE）。 */
private class FakeMemoryStore : MemoryStore {
    val nodes = LinkedHashMap<String, MemoryNode>()
    val writes = mutableListOf<MemoryWrite>()

    fun seed(vararg seedNodes: MemoryNode) {
        seedNodes.forEach { nodes[it.path.value] = it }
    }

    override suspend fun get(path: MemoryPath): MemoryNode? = nodes[path.value]

    override suspend fun children(
        path: MemoryPath,
        offset: Int,
        limit: Int,
    ): List<MemoryNode> =
        nodes.values
            .filter { it.path.parent == path }
            .drop(offset)
            .take(limit)

    override suspend fun write(w: MemoryWrite): Outcome<WriteResult> {
        val exists = w.path.value in nodes
        return when {
            w.mode == WriteMode.CREATE && exists ->
                Outcome.Err(AppError(code = CommonErrorCodes.INVALID_ARGUMENT, message = "exists"))
            w.mode == WriteMode.UPDATE && !exists ->
                Outcome.Err(AppError(code = CommonErrorCodes.NOT_FOUND, message = "missing"))
            else -> {
                writes += w // 仅记录实际落库的写入（UPDATE 缺失回退 CREATE 的尝试不计）
                val saved =
                    MemoryNode(
                        path = w.path,
                        title = w.title ?: w.path.name,
                        summary = w.summary.orEmpty(),
                        content = w.content,
                        tags = w.tags.orEmpty(),
                        links = w.links.orEmpty(),
                        source = w.source,
                        version = (nodes[w.path.value]?.version ?: 0) + 1,
                        createdAt = 1L,
                        updatedAt = 2L,
                        childCount = 0,
                    )
                nodes[w.path.value] = saved
                Outcome.Ok(
                    WriteResult(
                        node = saved,
                        versionId = 1L,
                        appliedMode = if (exists) AppliedMode.UPDATED else AppliedMode.CREATED,
                    ),
                )
            }
        }
    }

    override suspend fun delete(
        path: MemoryPath,
        recursive: Boolean,
        source: MemorySource,
        runId: RunId?,
    ): Outcome<Int> = Outcome.Ok(0)

    override suspend fun move(
        from: MemoryPath,
        to: MemoryPath,
    ): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun versions(
        path: MemoryPath,
        limit: Int,
    ): List<MemoryVersion> = emptyList()

    override suspend fun revert(versionId: Long): Outcome<WriteResult> =
        Outcome.Err(AppError(code = CommonErrorCodes.NOT_FOUND, message = "unused"))

    override suspend fun recentChanges(
        offset: Int,
        limit: Int,
        source: MemorySource?,
    ): List<MemoryVersion> = emptyList()

    override suspend fun changesOfRun(runId: RunId): List<MemoryVersion> = emptyList()

    override suspend fun revertRun(runId: RunId): Outcome<Int> = Outcome.Ok(0)

    override fun observeTree(): Flow<Unit> = MutableStateFlow(Unit)

    override suspend fun markConfirmed(path: MemoryPath) = Unit
}

/** 内存版 [TaskRepository]：空白名称的 `save` 返回 `task.invalid`（导入时跳过）。 */
private class FakeTaskRepository(
    initial: List<Task>,
) : TaskRepository {
    val tasks = mutableListOf<Task>()

    init {
        tasks += initial
    }

    override fun observeTasks(): Flow<List<Task>> = flowOf(tasks.toList())

    override suspend fun get(id: TaskId): Task? = tasks.firstOrNull { it.id == id }

    override suspend fun save(task: Task): Outcome<Task> =
        if (task.name.isBlank()) {
            Outcome.Err(AppError(code = "task.invalid", message = "blank name"))
        } else {
            tasks.removeAll { it.id == task.id }
            tasks += task
            Outcome.Ok(task)
        }

    override suspend fun delete(id: TaskId) {
        tasks.removeAll { it.id == id }
    }

    override suspend fun setEnabled(
        id: TaskId,
        enabled: Boolean,
    ) = Unit

    override fun observeRuns(
        taskId: TaskId?,
        limit: Int,
    ): Flow<List<RunRecord>> = flowOf(emptyList())
}
