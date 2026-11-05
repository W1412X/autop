/**
 * 记忆向量索引器（docs/tech/06 §2.5）：单协程消费队列，按段落切块向量化，
 * `textHash` 未变化的块跳过；失败退避重试，超过次数记日志放弃（下次写入时重来）。
 *
 * 配置的 embedding 模型变化（modelId 不同）→ 全量重建：删除旧向量、所有 path 入队。
 * `EmbeddingGateway.current() == null`（`llm.embedding.provider = none`）→ 空转。
 * 手动重建（docs/tech/10 WP10 设置页"重建记忆索引"）走 [rebuildAll]，逐条报进度。
 */
package com.example.autop.memory.impl

import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.LlmConfig
import com.example.autop.core.config.MemoryConfig
import com.example.autop.llm.EmbeddingGateway
import com.example.autop.llm.EmbeddingProvider
import com.example.autop.memory.MemoryIndexMaintenance
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.db.MemoryVectorEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** 记忆向量索引器。 */
@Singleton
@Suppress("LongParameterList") // 装配类：依赖都是索引器的正交能力
public class EmbeddingIndexer
    @Inject
    constructor(
        private val db: MemoryDb,
        private val gateway: EmbeddingGateway,
        private val configProvider: ConfigProvider,
        private val vectorCache: VectorCache,
        dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) : MemoryIndexMaintenance {
        private val queue = Channel<String>(Channel.UNLIMITED)
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.default + logger.exceptionHandler("EmbeddingIndexer"))

        init {
            scope.launch {
                for (path in queue) {
                    runCatching { index(path) }
                        .onFailure { logger.w(TAG, "index_failed", "path" to path, error = it) }
                }
            }
            scope.launch {
                gateway.changes.collect { provider ->
                    // 单次重建失败不能杀死订阅（否则后续模型切换静默丢失）
                    runCatching { onProviderChanged(provider?.modelId) }
                        .onFailure { logger.e(TAG, "provider_change_failed", error = it) }
                }
            }
        }

        /** 单个 path 入队（写入后调用）。 */
        public fun enqueue(path: String) {
            queue.trySend(path)
        }

        /**
         * 全量重建（设置页"重建记忆索引"，docs/tech/10 WP10）：删除非当前 modelId 的
         * 向量后逐条重新向量化，按条目报告 `done/total` 进度。
         */
        override suspend fun rebuildAll(onProgress: (done: Int, total: Int) -> Unit) {
            val provider = gateway.current() ?: return
            db.vectorDao().deleteExcept(provider.modelId)
            vectorCache.load(provider.modelId, emptyList())
            val paths = db.nodeDao().allPaths()
            onProgress(0, paths.size)
            paths.forEachIndexed { index, path ->
                runCatching { index(path) }
                    .onFailure { logger.w(TAG, "rebuild_index_failed", "path" to path, error = it) }
                onProgress(index + 1, paths.size)
            }
        }

        private suspend fun onProviderChanged(modelId: String?) {
            if (modelId == null || modelId == vectorCache.modelId) return
            db.vectorDao().deleteExcept(modelId)
            vectorCache.load(modelId, emptyList())
            db.nodeDao().allPaths().forEach { queue.trySend(it) }
        }

        @Suppress("ReturnCount") // 出口按“未配置/无变更/批量完成”分支，是流程语义
        private suspend fun index(path: String) {
            val provider = gateway.current() ?: return
            val node = db.nodeDao().get(path) ?: return
            val cfg = configProvider.current()[MemoryConfig.EMBEDDING]
            val chunks = MemoryText.chunkForEmbedding(node.title, node.path, node.content, cfg.chunkChars)
            val existing = db.vectorDao().byPath(path, provider.modelId)
            val pending =
                chunks
                    .mapIndexed { chunk, text -> chunk to text }
                    .filter { (chunk, text) ->
                        existing.none { it.chunk == chunk && it.textHash == MemoryText.textHash(text) }
                    }
            if (pending.isEmpty()) return
            val batchSize = configProvider.current()[LlmConfig.EMBEDDING].batchSize
            pending.chunked(batchSize).forEach { batch ->
                val vectors = embedWithRetry(provider, batch.map { it.second }, cfg.retryMax, cfg.retryBaseMs) ?: return
                batch.forEachIndexed { i, (chunk, text) ->
                    val vector = vectors.getOrNull(i) ?: return@forEachIndexed
                    db.vectorDao().upsert(
                        MemoryVectorEntity(
                            path = path,
                            chunk = chunk,
                            modelId = provider.modelId,
                            dim = vector.size,
                            vector = vector.toByteArray(),
                            textHash = MemoryText.textHash(text),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    vectorCache.upsert(path, chunk, vector)
                }
            }
        }

        private suspend fun embedWithRetry(
            provider: EmbeddingProvider,
            texts: List<String>,
            retryMax: Int,
            retryBaseMs: Long,
        ): List<FloatArray>? {
            var delay = retryBaseMs
            repeat(retryMax + 1) { attempt ->
                when (val r = provider.embed(texts)) {
                    is com.example.autop.core.common.Outcome.Ok -> return r.value
                    is com.example.autop.core.common.Outcome.Err -> {
                        logger.w(TAG, "embed_retry: attempt=$attempt code=${r.error.code}")
                        if (attempt < retryMax) {
                            kotlinx.coroutines.delay(delay)
                            delay *= 2
                        }
                    }
                }
            }
            return null
        }

        private fun FloatArray.toByteArray(): ByteArray {
            val buf = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            forEach { buf.putFloat(it) }
            return buf.array()
        }

        private companion object {
            const val TAG: String = "MemoryEmbedding"
        }
    }
