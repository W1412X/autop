/**
 * 数据导出（docs/tech/08 §4.5"导出数据"）：经领域 API（MemoryStore / TaskRepository /
 * ModelProfileStore）编排成 [BackupFile]。密钥不导出（`apiKeyRef` 置空）。
 */
package com.example.autop.feature.settings.me.backup

import com.example.autop.core.common.Clock
import com.example.autop.llm.ModelProfileStore
import com.example.autop.memory.MemoryNode
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemoryStore
import com.example.autop.task.TaskRepository
import kotlinx.coroutines.flow.first

/** 导出编排器（由 ViewModel 构造）。 */
public class BackupExporter(
    private val memoryStore: MemoryStore,
    private val taskRepository: TaskRepository,
    private val modelProfileStore: ModelProfileStore,
    private val clock: Clock,
) {
    /** 汇总当前全部数据。 */
    public suspend fun export(): BackupFile =
        BackupFile(
            format = BackupFormat.FORMAT,
            version = BackupFormat.VERSION,
            exportedAt = clock.nowMillis(),
            memories = collectMemories(),
            tasks = taskRepository.observeTasks().first(),
            // 导出不含密钥（docs/tech/03 §3：导入后需重新输入）。
            modelProfiles = modelProfileStore.profiles.first().map { it.copy(apiKeyRef = "") },
            defaultProfileId = modelProfileStore.defaultProfileId.first(),
        )

    /** 先序遍历记忆树（子节点分页拉全）。 */
    @Suppress("LoopWithTooManyJumpStatements") // 分页拉取循环的两个 break 各自表达“无更多页”
    private suspend fun collectMemories(): List<MemoryNode> {
        val result = mutableListOf<MemoryNode>()
        val queue = ArrayDeque(listOf(MemoryPath.ROOT))
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            var offset = 0
            while (true) {
                val page = memoryStore.children(path, offset = offset, limit = PAGE_SIZE)
                if (page.isEmpty()) break
                result.addAll(page)
                queue.addAll(page.map { it.path })
                if (page.size < PAGE_SIZE) break
                offset += page.size
            }
        }
        return result.sortedBy { it.path.value }
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}
