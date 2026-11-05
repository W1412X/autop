/**
 * EmbeddingIndexer 测试（docs/tech/06 §2.5/§7）：向量缓存增量更新——
 * 文本指纹未变的块跳过、写入后入缓存、modelId 变化全量重建（FakeEmbeddingProvider）。
 */
package com.example.autop.memory.impl

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.core.common.getOrNull
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryWrite
import com.example.autop.memory.WriteMode
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.testing.FakeEmbeddingGateway
import com.example.autop.memory.impl.testing.FakeEmbeddingProvider
import com.example.autop.memory.impl.testing.NoopLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 向量索引增量行为测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class EmbeddingIndexerTest {
    private lateinit var db: MemoryDb
    private lateinit var clock: FakeClock
    private lateinit var provider: FakeEmbeddingProvider
    private lateinit var gateway: FakeEmbeddingGateway
    private lateinit var cache: VectorCache
    private lateinit var store: MemoryStoreImpl
    private lateinit var indexer: EmbeddingIndexer

    @Before
    public fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java).build()
        clock = FakeClock(start = 1_000)
        provider = FakeEmbeddingProvider(modelId = "m1")
        gateway = FakeEmbeddingGateway(provider)
        cache = VectorCache()
        val config = FakeConfigProvider()
        val dispatchers = TestDispatcherProvider(Dispatchers.Default)
        indexer = EmbeddingIndexer(db, gateway, config, cache, dispatchers, NoopLogger)
        store =
            MemoryStoreImpl(
                db,
                FakePromptRepository(),
                config,
                indexer,
                MemorySeeder(db, emptySeed(), clock),
                dispatchers,
                clock,
                NoopLogger,
            )
    }

    @After
    public fun tearDown() {
        db.close()
    }

    @Test
    public fun `write indexes node and fills vector cache`(): Unit =
        runBlocking {
            writeNode("/tasks/向量", "正文内容")
            awaitCachePaths(1)

            assertThat(cache.topK(provider.vectorOf("正文内容"), 5).map { it.path }).contains("/tasks/向量")
        }

    @Test
    public fun `unchanged text is not re-embedded`(): Unit =
        runBlocking {
            writeNode("/tasks/幂等", "相同的正文")
            awaitCachePaths(1)
            settle()
            val callsAfterFirst = provider.calls.sumOf { it.size }

            indexer.enqueue("/tasks/幂等")
            settle()

            assertThat(provider.calls.sumOf { it.size }).isEqualTo(callsAfterFirst)
        }

    @Test
    public fun `content change re-embeds and updates cache`(): Unit =
        runBlocking {
            writeNode("/tasks/变更", "旧正文")
            awaitCachePaths(1)

            writeNodeContent("/tasks/变更", "完全不同的新正文", WriteMode.UPDATE)
            waitUntil {
                cache.topK(provider.vectorOf("完全不同的新正文"), 5).any { it.path == "/tasks/变更" }
            }
        }

    @Test
    public fun `provider switch rebuilds all vectors under new model`(): Unit =
        runBlocking {
            writeNode("/tasks/重建", "重建正文")
            awaitCachePaths(1)
            settle()

            val replacement = FakeEmbeddingProvider(modelId = "m2")
            gateway.switch(replacement)
            try {
                waitUntil {
                    cache.modelId == "m2" &&
                        cache.topK(replacement.vectorOf("重建正文"), 5).any { it.path == "/tasks/重建" }
                }
            } finally {
                replacementCalls = replacement.calls.sumOf { it.size }
            }

            assertThat(replacement.calls.sumOf { it.size }).isGreaterThan(0)
        }

    private suspend fun writeNode(
        path: String,
        content: String,
    ) = writeNodeContent(path, content, WriteMode.CREATE)

    private suspend fun writeNodeContent(
        path: String,
        content: String,
        mode: WriteMode,
    ) {
        store.write(
            MemoryWrite(
                path = MemoryPath.parse(path).getOrNull()!!,
                mode = mode,
                content = content,
                source = MemorySource.USER,
            ),
        )
    }

    private suspend fun awaitCachePaths(expected: Int) {
        waitUntil { cache.pathCount >= expected }
    }

    /** 等待后台索引队列静默（连续两次采样 embed 次数不变），消除与全量入队的竞态。 */
    private suspend fun settle() {
        var last = -1
        while (last != provider.calls.sumOf { it.size }) {
            last = provider.calls.sumOf { it.size }
            delay(SETTLE_MS)
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        val finished =
            runCatching {
                withTimeout(TIMEOUT_MS) {
                    while (!condition()) delay(POLL_MS)
                }
            }.isSuccess
        check(finished) {
            "waitUntil timed out: pathCount=${cache.pathCount} modelId=${cache.modelId} " +
                "embedCalls=${provider.calls} replacementCalls=$replacementCalls"
        }
    }

    private var replacementCalls: Int = 0

    private fun emptySeed(): MemorySeed =
        object : MemorySeed {
            override fun roots(): List<MemoryRootSeed> = emptyList()
        }

    private companion object {
        const val TIMEOUT_MS: Long = 5_000
        const val POLL_MS: Long = 20
        const val SETTLE_MS: Long = 100
    }
}
