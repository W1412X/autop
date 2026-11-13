/**
 * EmbeddingIndexer 测试（docs/tech/06 §2.5/§7，ADR-0028）：向量输入为标题与关键词——
 * 输入未变不重算、正文变化不重算、关键词变化重算、modelId 变化全量重建（FakeEmbeddingProvider）。
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
    private var replacementCalls: Int = 0

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
    public fun `write indexes title and keywords into the vector cache`(): Unit =
        runBlocking {
            write("/people/向量", content = "正文内容", keywords = listOf("同事"))
            awaitCachePaths(1)

            assertThat(cache.topK(provider.vectorOf("向量 同事"), 5).map { it.path }).contains("/people/向量")
        }

    @Test
    public fun `unchanged text is not re-embedded`(): Unit =
        runBlocking {
            write("/tasks/幂等", content = "相同的正文")
            awaitCachePaths(1)
            settle()
            val callsAfterFirst = provider.calls.sumOf { it.size }

            indexer.enqueue("/tasks/幂等")
            settle()

            assertThat(provider.calls.sumOf { it.size }).isEqualTo(callsAfterFirst)
        }

    @Test
    public fun `content change does not re-embed because content is not indexed`(): Unit =
        runBlocking {
            write("/tasks/正文", content = "旧正文")
            awaitCachePaths(1)
            settle()
            val callsAfterFirst = provider.calls.sumOf { it.size }

            write("/tasks/正文", content = "完全不同的新正文", mode = WriteMode.UPDATE)
            settle()

            assertThat(provider.calls.sumOf { it.size }).isEqualTo(callsAfterFirst)
        }

    @Test
    public fun `keyword change re-embeds and updates cache`(): Unit =
        runBlocking {
            write("/tasks/变更", content = "正文", keywords = listOf("旧词"))
            awaitCachePaths(1)

            write("/tasks/变更", content = "正文", keywords = listOf("新词"), mode = WriteMode.UPDATE)
            waitUntil {
                cache.topK(provider.vectorOf("变更 新词"), 5).any { it.path == "/tasks/变更" }
            }
        }

    @Test
    public fun `provider switch rebuilds all vectors under new model`(): Unit =
        runBlocking {
            write("/tasks/重建", content = "重建正文")
            awaitCachePaths(1)
            settle()

            val replacement = FakeEmbeddingProvider(modelId = "m2")
            gateway.switch(replacement)
            try {
                waitUntil {
                    cache.modelId == "m2" &&
                        cache.topK(replacement.vectorOf("重建"), 5).any { it.path == "/tasks/重建" }
                }
            } finally {
                replacementCalls = replacement.calls.sumOf { it.size }
            }

            assertThat(replacement.calls.sumOf { it.size }).isGreaterThan(0)
        }

    private suspend fun write(
        path: String,
        content: String,
        keywords: List<String> = emptyList(),
        mode: WriteMode = WriteMode.CREATE,
    ) {
        store.write(
            MemoryWrite(
                path = MemoryPath.parse(path).getOrNull()!!,
                mode = mode,
                content = content,
                keywords = keywords,
                source = MemorySource.USER,
            ),
        )
    }

    private suspend fun awaitCachePaths(expected: Int) {
        waitUntil { cache.pathCount >= expected }
    }

    /** 等待后台索引队列静默（连续三次采样 embed 次数不变），消除与全量入队的竞态。 */
    private suspend fun settle() {
        var last = -1
        var stable = 0
        while (stable < STABLE_SAMPLES) {
            val now = provider.calls.sumOf { it.size }
            stable = if (now == last) stable + 1 else 0
            last = now
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

    private fun emptySeed(): MemorySeed =
        object : MemorySeed {
            override fun roots(): List<MemoryRootSeed> = emptyList()
        }

    private companion object {
        /** CI 慢跑机上 Robolectric＋Room 冷启可能远超 5s（release 门禁曾抖动），放宽到 20s。 */
        const val TIMEOUT_MS: Long = 20_000
        const val POLL_MS: Long = 20
        const val SETTLE_MS: Long = 100

        /** settle 静默判定的连续稳定采样次数（× [SETTLE_MS]＝静默窗口）。 */
        const val STABLE_SAMPLES: Int = 3
    }
}
