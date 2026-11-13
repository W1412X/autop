/**
 * FTS 与向量检索通道测试（docs/tech/04 §2.5，ADR-0005）：
 * MATCH AND→OR 退化、摘要、切块向量、同 seq 多块取最高分、embedding 未配置跳过。
 */
package com.example.autop.agent.store.recall

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.store.ThreadFiles
import com.example.autop.agent.store.ThreadMappers
import com.example.autop.agent.store.ThreadStoreImpl
import com.example.autop.agent.store.db.ThreadsDb
import com.example.autop.agent.store.testing.FakeEmbeddingGateway
import com.example.autop.agent.store.testing.NoopLogger
import com.example.autop.agent.store.testing.SeqIdGenerator
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.recallChannelsOverride
import com.example.autop.llm.Block
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** 检索通道行为测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class RecallChannelsTest {
    private lateinit var db: ThreadsDb
    private lateinit var filesDir: File
    private lateinit var config: FakeConfigProvider
    private lateinit var embedding: FakeEmbeddingGateway
    private lateinit var queue: IndexQueue
    private lateinit var store: ThreadStoreImpl
    private lateinit var snippets: SnippetReader
    private lateinit var ftsRetriever: FtsRetriever
    private lateinit var vectorRetriever: VectorRetriever

    @Before
    public fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ThreadsDb::class.java).build()
        filesDir = Files.createTempDirectory("recall-test").toFile()
        config = FakeConfigProvider(recallChannelsOverride("recall.fts", "recall.vector"))
        embedding = FakeEmbeddingGateway()
        val files = ThreadFiles(filesDir)
        snippets = SnippetReader(db.eventDao(), files, config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val indexers =
            setOf(
                FtsIndexer(db.ftsDao(), snippets),
                VectorIndexer(db.vectorDao(), embedding, snippets, config, FakeClock()),
            )
        queue = IndexQueue(indexers, 500, config, scope, NoopLogger)
        store = ThreadStoreImpl(db, ThreadMappers(files, config), queue, FakeClock(), SeqIdGenerator())
        ftsRetriever = FtsRetriever(db.ftsDao(), snippets)
        vectorRetriever = VectorRetriever(db.vectorDao(), embedding, snippets)
    }

    @After
    public fun tearDown() {
        db.close()
        filesDir.deleteRecursively()
    }

    @Test
    public fun `fts retriever finds indexed user message with snippet`(): Unit =
        runBlocking {
            val thread = seed("今天天气很好", "我们去公园散步")

            val hits = ftsRetriever.search(thread, "天气很好", 10)

            assertThat(hits.map { it.seq }).containsExactly(1L)
            assertThat(hits.single().snippet).contains("天气")
        }

    @Test
    public fun `fts match falls back to OR when AND yields nothing`(): Unit =
        runBlocking {
            val thread = seed("苹果香蕉", "橘子葡萄")

            val hits = ftsRetriever.search(thread, "苹果香蕉橘子葡萄", 10)

            assertThat(hits.map { it.seq }).containsExactly(2L, 1L).inOrder()
        }

    @Test
    public fun `fts delete thread removes rows`(): Unit =
        runBlocking {
            val thread = seed("唯一内容")

            db.ftsDao().deleteOwned(thread.value)
            val hits = ftsRetriever.search(thread, "唯一", 10)

            assertThat(hits).isEmpty()
        }

    @Test
    public fun `vector retriever ranks exact text first`(): Unit =
        runBlocking {
            val thread = seed("查询目标文本", "其他内容", "查询目标文本")

            val hits = vectorRetriever.search(thread, "查询目标文本", 10)

            assertThat(hits.map { it.seq }).contains(1L)
            assertThat(hits.first().seq).isAnyOf(1L, 3L)
        }

    @Test
    public fun `vector channel chunks long text and keeps best score per seq`(): Unit =
        runBlocking {
            config.setOverrides(
                buildJsonObject {
                    put(
                        "agent",
                        buildJsonObject {
                            put(
                                "recall",
                                buildJsonObject {
                                    put(
                                        "channels",
                                        buildJsonArray {
                                            add(JsonPrimitive("recall.fts"))
                                            add(JsonPrimitive("recall.vector"))
                                        },
                                    )
                                    put("vector", buildJsonObject { put("chunkChars", 4) })
                                },
                            )
                        },
                    )
                },
            )
            val thread = seed("abcdefgh") // 8 字 → 2 块

            val rows = db.vectorDao().allOwned(thread.value)

            assertThat(rows.map { it.chunk }).containsExactly(0, 1)
            assertThat(rows.map { it.modelId }.toSet()).containsExactly("test-model")
            assertThat(vectorRetriever.search(thread, "abcdefgh", 5).map { it.seq }).containsExactly(1L)
        }

    @Test
    public fun `vector channel unavailable and silent without embedding`(): Unit =
        runBlocking {
            embedding.set(null)
            val thread = ThreadId("t6")
            val event =
                ThreadEvent(
                    thread,
                    1,
                    null,
                    0,
                    UserMessage(blocks = listOf(Block.Text("文本")), kind = UserMessageKind.INPUT),
                )

            queue.submit(event)
            queue.flush()

            assertThat(vectorRetriever.isAvailable()).isFalse()
            assertThat(db.vectorDao().allOwned("t6")).isEmpty()
        }

    @Test
    public fun `rebuild recreates indexes for thread`(): Unit =
        runBlocking {
            val thread = seed("重建索引文本")

            db.ftsDao().deleteOwned(thread.value)
            assertThat(db.ftsDao().match(thread.value, "重建", 5)).isEmpty()

            val events =
                listOf(
                    ThreadEvent(
                        thread,
                        1,
                        null,
                        0,
                        UserMessage(blocks = listOf(Block.Text("重建索引文本")), kind = UserMessageKind.INPUT),
                    ),
                )
            val indexer = FtsIndexer(db.ftsDao(), snippets)
            indexer.rebuild(thread, events)

            assertThat(ftsRetriever.search(thread, "重建", 5)).hasSize(1)
        }

    /** 经 [ThreadStoreImpl] 落库，保证事件行就位（SnippetReader 从事件行取文本）。 */
    private suspend fun seed(vararg texts: String): ThreadId {
        val thread = store.createThread(title = null, parent = null)
        texts.forEach { text ->
            store.append(
                thread,
                null,
                UserMessage(blocks = listOf(Block.Text(text)), kind = UserMessageKind.INPUT),
            )
        }
        queue.flush()
        return thread
    }

    @Test
    public fun `non-text events are not indexed`(): Unit =
        runBlocking {
            val thread = ThreadId("t8")
            val event =
                ThreadEvent(
                    thread,
                    1,
                    null,
                    0,
                    RunFinished(status = RunOutcome.SUCCEEDED, summary = "不入索引"),
                )
            queue.submit(event)
            queue.flush()

            assertThat(db.ftsDao().match(thread.value, "不入", 5)).isEmpty()
        }
}
