/**
 * 内置根目录（docs/tech/06 §2.2）：`/profile`、`/people`、`/apps`、`/tasks`。
 *
 * 标题与摘要来自 strings.xml，由启动时注入的 [MemorySeed] 提供，不在 SQL 中写死文字；
 * 根目录与四个内置目录不能删除、不能移动（`memory.root_protected`）。
 */
package com.example.autop.memory.impl

import android.content.Context
import com.example.autop.core.common.Clock
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.db.MemoryNodeEntity

/** 一个内置根目录的种子文案。 */
public data class MemoryRootSeed(
    val path: String,
    val title: String,
    val summary: String,
)

/** 内置根目录提供者（文案在 DI 装配时取自资源）。 */
public interface MemorySeed {
    public fun roots(): List<MemoryRootSeed>

    public companion object {
        /** 受保护的内置目录路径（含 "/"）。 */
        public val PROTECTED_ROOTS: List<String> = listOf("/", "/profile", "/people", "/apps", "/tasks")
    }
}

/** 首次打开数据库时幂等补齐根目录节点。 */
public class MemorySeeder(
    private val db: MemoryDb,
    private val seed: MemorySeed,
    private val clock: Clock,
) {
    suspend fun ensureRoots() {
        if (db.nodeDao().count() > 0) return
        val now = clock.nowMillis()
        insertIfAbsent(MEMORY_PATH_ROOT, parent = null, title = "", summary = "", now = now)
        for (root in seed.roots()) {
            insertIfAbsent(root.path, parent = MEMORY_PATH_ROOT, title = root.title, summary = root.summary, now = now)
        }
    }

    private suspend fun insertIfAbsent(
        path: String,
        parent: String?,
        title: String,
        summary: String,
        now: Long,
    ) {
        if (db.nodeDao().get(path) != null) return
        db.nodeDao().insertIfAbsent(
            MemoryNodeEntity(
                path = path,
                parentPath = parent,
                title = title,
                summary = summary,
                content = "",
                tagsJson = "[]",
                linksJson = "[]",
                source = "USER",
                version = 1,
                createdAt = now,
                updatedAt = now,
                accessCount = 0,
                lastAccessAt = null,
            ),
        )
    }
}

/** 记忆根路径常量。 */
private const val MEMORY_PATH_ROOT: String = "/"

/** 从模块资源读取内置根目录文案（不在 SQL 中写死文字）。 */
public class ResourceMemorySeed(
    private val context: Context,
) : MemorySeed {
    override fun roots(): List<MemoryRootSeed> =
        listOf(
            root("/profile", R.string.memory_root_profile_title, R.string.memory_root_profile_summary),
            root("/people", R.string.memory_root_people_title, R.string.memory_root_people_summary),
            root("/apps", R.string.memory_root_apps_title, R.string.memory_root_apps_summary),
            root("/tasks", R.string.memory_root_tasks_title, R.string.memory_root_tasks_summary),
        )

    private fun root(
        path: String,
        titleRes: Int,
        summaryRes: Int,
    ): MemoryRootSeed =
        MemoryRootSeed(
            path = path,
            title = context.getString(titleRes),
            summary = context.getString(summaryRes),
        )
}
