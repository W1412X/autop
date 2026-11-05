/**
 * 记忆索引维护端口（docs/tech/10 WP10，08 §4.5"重建记忆索引"进度）：
 * embedding 配置变化或用户手动触发时全量重建记忆向量索引。
 */
package com.example.autop.memory

/** 记忆索引维护。 */
public interface MemoryIndexMaintenance {
    /** 全量重建记忆向量索引；按条目报告 `done/total` 进度。 */
    public suspend fun rebuildAll(onProgress: (done: Int, total: Int) -> Unit)
}
