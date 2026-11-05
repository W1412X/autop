/**
 * HybridMemorySearch 测试（docs/tech/06 §2.6/§7）：RRF 融合、FTS AND→OR 退化、
 * snippet 兜底 summary、links 附行与命中计数。
 */
package com.example.autop.memory.impl

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryWrite
import com.example.autop.memory.WriteMode
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.retriever.FtsRetriever
import com.example.autop.memory.impl.retriever.PathRetriever
import com.example.autop.memory.impl.testing.FakeEmbeddingGateway
import com.example.autop.memory.impl.testing.FakeEmbeddingProvider
import com.example.autop.memory.impl.testing.NoopLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 混合检索行为测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class HybridMemorySearchTest {
    private lateinit var db: MemoryDb
    private lateinit var store: MemoryStoreImpl
    private lateinit var search: HybridMemorySearch

    @Before
    public fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java).build()
        val clock = FakeClock(start = 1_000)
        val config = FakeConfigProvider()
        val dispatchers =
            com.example.autop.core.testing
                .TestDispatcherProvider(Dispatchers.Default)
        val indexer =
            EmbeddingIndexer(
                db,
                FakeEmbeddingGateway(FakeEmbeddingProvider()),
                config,
                VectorCache(),
                dispatchers,
                NoopLogger,
            )
        store =
            MemoryStoreImpl(
                db,
                com.example.autop.core.testing
                    .FakePromptRepository(),
                config,
                indexer,
                MemorySeeder(db, emptySeed(), clock),
                dispatchers,
                clock,
                NoopLogger,
            )
        search = HybridMemorySearch(db, setOf(PathRetriever(db), FtsRetriever(db)), config, clock)
    }

    @After
    public fun tearDown() {
        db.close()
    }

    @Test
    public fun `fts falls back from AND to OR when no row matches all tokens`(): Unit =
        runBlocking {
            seedNode("/tasks/买牛奶", title = "买牛奶", content = "超市采购清单")
            seedNode("/tasks/还书", title = "还书", content = "图书馆借阅")

            val hits = search.search(query = "牛奶图书馆", prefix = null, k = 5)

            assertThat(hits.map { it.path.value }).contains("/tasks/买牛奶")
            assertThat(hits.map { it.path.value }).contains("/tasks/还书")
        }

    @Test
    public fun `multi-channel hit fuses above single-channel hit`(): Unit =
        runBlocking {
            seedNode("/people/张三", title = "张三", content = "同事")
            seedNode("/people/李四", title = "李四", content = "张三的好朋友，同组")

            val hits = search.search(query = "张三", prefix = null, k = 5)

            assertThat(hits.map { it.path.value }).contains("/people/张三")
            assertThat(hits.map { it.path.value }).contains("/people/李四")
        }

    @Test
    public fun `hit snippet falls back to summary and appends link lines`(): Unit =
        runBlocking {
            seedNode("/tasks/引用", title = "引用", content = "正文", links = listOf("/tasks/被引用"))
            seedNode("/tasks/被引用", title = "被引用", content = "相关条目")

            val hits = search.search(query = "引用", prefix = null, k = 5)

            val hit = hits.first { it.path.value == "/tasks/引用" }
            assertThat(hit.snippet).contains("被引用")
        }

    @Test
    public fun `search touches access count`(): Unit =
        runBlocking {
            seedNode("/tasks/计数", title = "计数", content = "计数内容")

            search.search(query = "计数", prefix = null, k = 5)

            val node = db.nodeDao().get("/tasks/计数")!!
            assertThat(node.accessCount).isEqualTo(1)
        }

    @Test
    public fun `prefix narrows results to subtree`(): Unit =
        runBlocking {
            seedNode("/apps/微信/设置", title = "设置", content = "微信设置")
            seedNode("/people/设置达人", title = "设置达人", content = "达人")

            val hits = search.search(query = "设置", prefix = MemoryPath.parse("/apps").getOrNull()!!, k = 5)

            assertThat(hits.map { it.path.value }).contains("/apps/微信/设置")
            assertThat(hits.map { it.path.value }).doesNotContain("/people/设置达人")
        }

    private suspend fun seedNode(
        path: String,
        title: String,
        content: String,
        links: List<String> = emptyList(),
    ) {
        val result =
            store.write(
                MemoryWrite(
                    path = MemoryPath.parse(path).getOrNull()!!,
                    mode = WriteMode.CREATE,
                    title = title,
                    content = content,
                    links = links.map { MemoryPath.parse(it).getOrNull()!! },
                    source = MemorySource.USER,
                ),
            )
        check(result is Outcome.Ok) { "seed failed: $path" }
    }

    private fun emptySeed(): MemorySeed =
        object : MemorySeed {
            override fun roots(): List<MemoryRootSeed> = emptyList()
        }
}
