/**
 * FTS 同步（docs/tech/06 §2.3）：节点写入/删除/移动时同步 `memory_fts` 分词列。
 */
package com.example.autop.memory.impl

import com.example.autop.core.common.BigramTokenizer
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.db.MemoryFtsEntity
import com.example.autop.memory.impl.db.MemoryNodeEntity

/** `memory_fts` 与节点表的同步器。 */
internal class MemoryFtsSync(
    private val db: MemoryDb,
) {
    /** 按节点现状重写其 FTS 行。 */
    suspend fun syncNode(node: MemoryNodeEntity) {
        val pathSegments =
            node.path
                .split('/')
                .filter { it.isNotEmpty() }
                .joinToString(separator = " ")
        db.ftsDao().upsert(
            MemoryFtsEntity(
                path = node.path,
                titleTokens = BigramTokenizer.joinForIndex(node.title + " " + pathSegments),
                bodyTokens =
                    BigramTokenizer.joinForIndex(
                        node.summary + " " + node.content + " " + node.tagsJson,
                    ),
            ),
        )
    }

    /** 删除单行。 */
    suspend fun remove(path: String) {
        db.ftsDao().delete(path)
    }

    /** 删除子树全部行。 */
    suspend fun removeUnder(prefix: String) {
        db.ftsDao().deleteUnder(prefix)
    }
}
