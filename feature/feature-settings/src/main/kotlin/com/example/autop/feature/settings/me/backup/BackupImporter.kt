/**
 * 数据导入（docs/tech/08 §4.5"导入数据"，WP11 验收：导出 → 清除数据 → 导入后
 * 记忆、任务、模型配置恢复）：校验 `format`/`version` 后逐域写回。
 * 模型配置的 `apiKeyRef` 为空，导入后需重新输入密钥。
 */
package com.example.autop.feature.settings.me.backup

import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.llm.ModelProfileStore
import com.example.autop.memory.MemoryNode
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryStore
import com.example.autop.memory.MemoryWrite
import com.example.autop.memory.WriteMode
import com.example.autop.task.Task
import com.example.autop.task.TaskRepository
import kotlinx.serialization.SerializationException

/** 导入编排器（由 ViewModel 构造）。 */
public class BackupImporter(
    private val memoryStore: MemoryStore,
    private val taskRepository: TaskRepository,
    private val modelProfileStore: ModelProfileStore,
) {
    /** 解析并恢复 [json]；格式不合法返回 `backup.invalid`。 */
    @Suppress("ReturnCount") // 解析失败 / 格式不合法 / 成功三段各自提前返回
    public suspend fun import(json: String): Outcome<ImportResult> {
        val bundle =
            try {
                AutopJson.default.decodeFromString(BackupFile.serializer(), json)
            } catch (e: SerializationException) {
                return Outcome.Err(AppError(code = CODE_INVALID, message = e.message ?: e.toString(), cause = e))
            }
        if (bundle.format != BackupFormat.FORMAT || bundle.version > BackupFormat.VERSION) {
            return Outcome.Err(
                AppError(code = CODE_INVALID, message = "unsupported backup: ${bundle.format} v${bundle.version}"),
            )
        }
        return Outcome.Ok(
            ImportResult(
                memories = importMemories(bundle.memories),
                tasks = importTasks(bundle.tasks),
                modelProfiles = importProfiles(bundle),
            ),
        )
    }

    /** 记忆逐条写回（先父后子由排序保证；已存在则更新）。 */
    private suspend fun importMemories(nodes: List<MemoryNode>): Int {
        var count = 0
        for (node in nodes.sortedBy { it.path.segments().size }) {
            val write =
                MemoryWrite(
                    path = node.path,
                    mode = WriteMode.UPDATE,
                    title = node.title,
                    summary = node.summary,
                    content = node.content,
                    tags = node.tags,
                    links = node.links,
                    source = MemorySource.IMPORT,
                    reason = "backup import",
                )
            val applied =
                if (memoryStore.write(write).getOrNull() != null) {
                    true
                } else {
                    memoryStore.write(write.copy(mode = WriteMode.CREATE)).getOrNull() != null
                }
            if (applied) count++
        }
        return count
    }

    /** 任务逐条保存（保存即校验并重排闹钟；不合法的跳过）。 */
    private suspend fun importTasks(tasks: List<Task>): Int =
        tasks.count { task -> taskRepository.save(task).getOrNull() != null }

    /** 模型配置与角色恢复（`apiKeyRef` 保持为空，需重新输入密钥）。 */
    private suspend fun importProfiles(bundle: BackupFile): Int {
        var count = 0
        for (profile in bundle.modelProfiles) {
            modelProfileStore.upsert(profile.copy(apiKeyRef = ""))
            count++
        }
        modelProfileStore.setDefaultProfileId(bundle.defaultProfileId ?: bundle.legacyModelRoles?.main)
        return count
    }

    private companion object {
        const val CODE_INVALID = "backup.invalid"
    }
}
