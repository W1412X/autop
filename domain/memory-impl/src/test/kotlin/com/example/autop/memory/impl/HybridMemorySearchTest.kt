/**
 * HybridMemorySearch 测试（docs/tech/06 §2.6/§7，ADR-0028）：关键词通道的匹配规则（查询中出现检索词即命中、
 * 过短检索词不参与、标题与路径名作为隐含关键词）、前缀过滤、goal 预取的阈值与过滤规则。
 */
package com.example.autop.memory.impl

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.core.common.Outcome
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
import com.example.autop.memory.impl.retriever.KeywordRetriever
import com.example.autop.memory.impl.testing.FakeEmbeddingGateway
import com.example.autop.memory.impl.testing.FakeEmbeddingProvider
import com.example.autop.memory.impl.testing.NoopLogger
import com.example.autop.memory.impl.testing.TestSeed
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 关键词检索与预取行为测试。 */
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
        val dispatchers = TestDispatcherProvider(Dispatchers.Default)
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
                FakePromptRepository(),
                config,
                indexer,
                MemorySeeder(db, TestSeed, clock),
                dispatchers,
                clock,
                NoopLogger,
            )
        search = HybridMemorySearch(db, setOf(KeywordRetriever(db, config)), config, clock)
    }

    @After
    public fun tearDown() {
        db.close()
    }

    @Test
    public fun `a keyword that appears in the query finds the node`(): Unit =
        runBlocking {
            seed("/people/张三", title = "张三", keywords = listOf("张三", "产品经理"))

            val hits = search.search(query = "给张三回消息说会议改到4点", prefix = null, k = 5)

            assertThat(hits.map { it.path.value }).containsExactly("/people/张三")
        }

    @Test
    public fun `a one-character cjk keyword does not match`(): Unit =
        runBlocking {
            seed("/tasks/习惯", title = "习惯", keywords = listOf("我"))

            val hits = search.search(query = "我今天很忙", prefix = null, k = 5)

            assertThat(hits).isEmpty()
        }

    @Test
    public fun `title and path name act as implicit keywords`(): Unit =
        runBlocking {
            seed("/apps/com.tencent.mm/_manual", title = "微信", keywords = emptyList(), summary = "操作说明")

            val hits = search.search(query = "帮我打开微信", prefix = null, k = 5)

            assertThat(hits.map { it.path.value }).contains("/apps/com.tencent.mm/_manual")
        }

    @Test
    public fun `prefix restricts results to the subtree`(): Unit =
        runBlocking {
            seed("/people/张三", title = "张三", keywords = listOf("张三"))
            seed("/apps/张三工具/_manual", title = "张三工具", keywords = listOf("张三"))

            val hits = search.search(query = "张三", prefix = MemoryPath.parse("/apps").getOrNull()!!, k = 5)

            assertThat(hits.map { it.path.value }).contains("/apps/张三工具/_manual")
            assertThat(hits.map { it.path.value }).doesNotContain("/people/张三")
        }

    @Test
    public fun `prefetch returns matches at or above the keyword score floor`(): Unit =
        runBlocking {
            seed("/people/张三", title = "张三", keywords = listOf("张三"))

            val hits = search.prefetch(query = "给张三回消息", k = 3, minKeywordScore = 2.0)

            assertThat(hits.map { it.path.value }).containsExactly("/people/张三")
        }

    @Test
    public fun `prefetch drops matches below the floor`(): Unit =
        runBlocking {
            seed("/people/张三", title = "张三", keywords = listOf("张三"))

            val hits = search.prefetch(query = "给张三回消息", k = 3, minKeywordScore = 5.0)

            assertThat(hits).isEmpty()
        }

    @Test
    public fun `prefetch skips built-in roots and entries without content`(): Unit =
        runBlocking {
            seed("/tasks/空目录", title = "空目录", keywords = listOf("空目录"), content = "", summary = "")

            assertThat(search.prefetch(query = "人物", k = 3, minKeywordScore = 2.0)).isEmpty()
            assertThat(search.prefetch(query = "空目录的事", k = 3, minKeywordScore = 2.0)).isEmpty()
        }

    private suspend fun seed(
        path: String,
        title: String,
        keywords: List<String>,
        summary: String = "摘要",
        content: String = "正文",
    ) {
        val result =
            store.write(
                MemoryWrite(
                    path = MemoryPath.parse(path).getOrNull()!!,
                    mode = WriteMode.CREATE,
                    title = title,
                    summary = summary,
                    content = content,
                    keywords = keywords,
                    source = MemorySource.USER,
                ),
            )
        check(result is Outcome.Ok) { "seed failed: $path $result" }
    }
}
