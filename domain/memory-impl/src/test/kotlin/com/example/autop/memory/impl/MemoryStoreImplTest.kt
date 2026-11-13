/**
 * MemoryStoreImpl 测试（docs/tech/06 §2.4/§7）：写入四模式与用户内容保护、
 * 版本与撤销（单条、按 Run）、删除与移动的根保护。
 */
package com.example.autop.memory.impl

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.getOrNull
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.memory.AppliedMode
import com.example.autop.memory.MemoryErrorCodes
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryWrite
import com.example.autop.memory.WriteMode
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.db.MemoryNodeEntity
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

/** 写入与撤销行为测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class MemoryStoreImplTest {
    private lateinit var db: MemoryDb
    private lateinit var clock: FakeClock
    private lateinit var provider: FakeEmbeddingProvider
    private lateinit var store: MemoryStoreImpl

    @Before
    public fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java).build()
        clock = FakeClock(start = 1_000)
        provider = FakeEmbeddingProvider()
        val config = FakeConfigProvider()
        val dispatchers = TestDispatcherProvider(Dispatchers.Default)
        val indexer =
            EmbeddingIndexer(db, FakeEmbeddingGateway(provider), config, VectorCache(), dispatchers, NoopLogger)
        val seeder = MemorySeeder(db, TestSeed, clock)
        store = MemoryStoreImpl(db, FakePromptRepository(), config, indexer, seeder, dispatchers, clock, NoopLogger)
    }

    @After
    public fun tearDown() {
        db.close()
    }

    @Test
    public fun `create makes parents and records version`(): Unit =
        runBlocking {
            val path = MemoryPath.parse("/people/张三/喜好").getOrNull()!!

            val result = store.write(write(path, WriteMode.CREATE, content = "喜欢红茶"))

            val created = (result as Outcome.Ok).value
            assertThat(created.appliedMode).isEqualTo(AppliedMode.CREATED)
            assertThat(created.node.version).isEqualTo(1)
            assertThat(store.get(MemoryPath.parse("/people/张三").getOrNull()!!)).isNotNull()
            val versions = store.versions(path, limit = 10)
            assertThat(versions.single().changeType.name).isEqualTo("CREATE")
        }

    @Test
    public fun `create on existing path fails with already exists`(): Unit =
        runBlocking {
            val path = MemoryPath.parse("/tasks/购物").getOrNull()!!
            store.write(write(path, WriteMode.CREATE, content = "买牛奶"))

            val second = store.write(write(path, WriteMode.CREATE, content = "再写一次"))

            val err = (second as Outcome.Err).error
            assertThat(err.code).isEqualTo(MemoryErrorCodes.ALREADY_EXISTS)
        }

    @Test
    public fun `update on agent content replaces it`(): Unit =
        runBlocking {
            val path = MemoryPath.parse("/tasks/日程").getOrNull()!!
            store.write(write(path, WriteMode.CREATE, content = "旧内容", source = MemorySource.AGENT))

            val result = store.write(write(path, WriteMode.UPDATE, content = "新内容", source = MemorySource.AGENT))

            val updated = (result as Outcome.Ok).value
            assertThat(updated.appliedMode).isEqualTo(AppliedMode.UPDATED)
            assertThat(updated.node.content).isEqualTo("新内容")
            assertThat(updated.node.version).isEqualTo(2)
        }

    @Test
    public fun `agent update on user content downgrades to supplement`(): Unit =
        runBlocking {
            val path = MemoryPath.parse("/profile/习惯").getOrNull()!!
            store.write(write(path, WriteMode.CREATE, content = "用户手写", title = "用户标题", source = MemorySource.USER))

            val result = store.write(write(path, WriteMode.UPDATE, content = "agent 想改写", source = MemorySource.AGENT))

            val supplemented = (result as Outcome.Ok).value
            assertThat(supplemented.appliedMode).isEqualTo(AppliedMode.APPENDED_AS_SUPPLEMENT)
            assertThat(supplemented.node.title).isEqualTo("用户标题")
            assertThat(supplemented.node.content).contains("用户手写")
            assertThat(supplemented.node.content).contains("agent 想改写")
        }

    @Test
    public fun `append merges content and bumps version`(): Unit =
        runBlocking {
            val path = MemoryPath.parse("/tasks/清单").getOrNull()!!
            store.write(write(path, WriteMode.CREATE, content = "第一条"))

            val result = store.write(write(path, WriteMode.APPEND, content = "第二条"))

            val appended = (result as Outcome.Ok).value
            assertThat(appended.appliedMode).isEqualTo(AppliedMode.APPENDED)
            assertThat(appended.node.content).isEqualTo("第一条\n\n第二条")
        }

    @Test
    public fun `revert restores content before the change`(): Unit =
        runBlocking {
            val path = MemoryPath.parse("/tasks/撤销").getOrNull()!!
            store.write(write(path, WriteMode.CREATE, content = "原始"))
            val update = store.write(write(path, WriteMode.UPDATE, content = "改坏"))

            store.revert((update as Outcome.Ok).value.versionId)

            assertThat(store.get(path)!!.content).isEqualTo("原始")
        }

    @Test
    public fun `revertRun rolls back all changes of the run`(): Unit =
        runBlocking {
            val runId = RunId("run-1")
            val a = MemoryPath.parse("/tasks/甲").getOrNull()!!
            val b = MemoryPath.parse("/tasks/乙").getOrNull()!!
            store.write(write(a, WriteMode.CREATE, content = "甲").copy(runId = runId))
            store.write(write(b, WriteMode.CREATE, content = "乙").copy(runId = runId))

            val reverted = store.revertRun(runId)

            assertThat((reverted as Outcome.Ok).value).isEqualTo(2)
            assertThat(store.get(a)).isNull()
            assertThat(store.get(b)).isNull()
        }

    @Test
    public fun `delete protects built-in roots and requires recursive for non-empty dir`(): Unit =
        runBlocking {
            val root = MemoryPath.parse("/people").getOrNull()!!
            val dir = MemoryPath.parse("/tasks/工作").getOrNull()!!
            store.write(write(dir, WriteMode.CREATE, content = "目录"))
            val child = MemoryPath.parse("/tasks/工作/日报").getOrNull()!!
            store.write(write(child, WriteMode.CREATE, content = "日报"))

            val rootErr =
                (store.delete(root, recursive = true, source = MemorySource.USER, runId = null) as Outcome.Err)
                    .error
            val dirErr =
                (store.delete(dir, recursive = false, source = MemorySource.USER, runId = null) as Outcome.Err)
                    .error

            assertThat(rootErr.code).isEqualTo(MemoryErrorCodes.ROOT_PROTECTED)
            assertThat(dirErr.code).isEqualTo(MemoryErrorCodes.ALREADY_EXISTS)
            val removed = store.delete(dir, recursive = true, source = MemorySource.USER, runId = null)
            assertThat((removed as Outcome.Ok).value).isEqualTo(2)
        }

    @Test
    public fun `move relocates subtree and protects roots`(): Unit =
        runBlocking {
            val from = MemoryPath.parse("/tasks/搬家").getOrNull()!!
            store.write(write(from, WriteMode.CREATE, content = "内容"))
            val to = MemoryPath.parse("/tasks/已搬家").getOrNull()!!

            val result = store.move(from, to)

            assertThat(result).isInstanceOf(Outcome.Ok::class.java)
            assertThat(store.get(from)).isNull()
            assertThat(store.get(to)!!.content).isEqualTo("内容")
            val rootErr =
                (
                    store.move(
                        MemoryPath.parse("/tasks").getOrNull()!!,
                        MemoryPath.parse("/别的").getOrNull()!!,
                    ) as Outcome.Err
                ).error
            assertThat(rootErr.code).isEqualTo(MemoryErrorCodes.ROOT_PROTECTED)
        }

    @Test
    public fun `markConfirmed promotes agent node to user`(): Unit =
        runBlocking {
            val path = MemoryPath.parse("/apps/确认").getOrNull()!!
            store.write(write(path, WriteMode.CREATE, content = "agent 写的", source = MemorySource.AGENT))

            store.markConfirmed(path)

            assertThat(store.get(path)!!.source).isEqualTo(MemorySource.USER)
            val confirm = store.versions(path, limit = 10).first()
            assertThat(confirm.changeType.name).isEqualTo("CONFIRM")
        }

    @Test
    public fun `recursive delete leaves siblings whose names share a prefix or contain underscore`(): Unit =
        runBlocking {
            val parent = MemoryPath.parse("/tasks/报_告").getOrNull()!!
            val similarName = MemoryPath.parse("/tasks/报X告").getOrNull()!!
            val nameSibling = MemoryPath.parse("/tasks/报_告丰").getOrNull()!!
            store.write(write(parent, WriteMode.CREATE, content = "父", source = MemorySource.USER))
            store.write(write(similarName, WriteMode.CREATE, content = "下划线通配", source = MemorySource.USER))
            store.write(write(nameSibling, WriteMode.CREATE, content = "前缀相同", source = MemorySource.USER))

            store.delete(parent, recursive = true, source = MemorySource.USER, runId = null)

            assertThat(store.get(parent)).isNull()
            assertThat(store.get(similarName)).isNotNull()
            assertThat(store.get(nameSibling)).isNotNull()
        }

    @Test
    public fun `agent create with a same-named sibling is rejected unless force_new`(): Unit =
        runBlocking {
            val first = MemoryPath.parse("/people/李四").getOrNull()!!
            store.write(write(first, WriteMode.CREATE, content = "同学", title = "李四", source = MemorySource.USER))
            val dupPath = MemoryPath.parse("/people/李四二").getOrNull()!!

            val rejected =
                store.write(write(dupPath, WriteMode.CREATE, content = "又一条", title = " 李四 "))

            assertThat((rejected as Outcome.Err).error.code).isEqualTo(MemoryErrorCodes.POSSIBLE_DUPLICATE)
            assertThat(store.get(dupPath)).isNull()

            val forced =
                store.write(
                    write(dupPath, WriteMode.CREATE, content = "确实是另一个人", title = "李四").copy(forceNew = true),
                )
            assertThat(forced).isInstanceOf(Outcome.Ok::class.java)
        }

    @Test
    public fun `user create with a same-named sibling is allowed`(): Unit =
        runBlocking {
            val first = MemoryPath.parse("/people/王五").getOrNull()!!
            store.write(write(first, WriteMode.CREATE, content = "甲", title = "王五", source = MemorySource.USER))
            val second = MemoryPath.parse("/people/王五二").getOrNull()!!

            val result =
                store.write(write(second, WriteMode.CREATE, content = "乙", title = "王五", source = MemorySource.USER))

            assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        }

    @Test
    public fun `ensureRoots adds a missing root to an existing database`(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val legacy = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java).build()
            legacy.nodeDao().insertIfAbsent(
                MemoryNodeEntity(
                    path = "/tasks/旧数据",
                    parentPath = "/tasks",
                    title = "旧数据",
                    summary = "",
                    content = "保留",
                    keywordsJson = "[]",
                    linksJson = "[]",
                    source = "AGENT",
                    version = 1,
                    createdAt = 1,
                    updatedAt = 1,
                    accessCount = 0,
                    lastAccessAt = null,
                ),
            )

            MemorySeeder(legacy, TestSeed, clock).ensureRoots()

            assertThat(legacy.nodeDao().get("/events")?.title).isEqualTo("事件")
            assertThat(legacy.nodeDao().get("/tasks/旧数据")?.content).isEqualTo("保留")
            legacy.close()
        }

    @Test
    public fun `agent create without summary or keywords is rejected`(): Unit =
        runBlocking {
            val noSummary =
                store.write(
                    write(MemoryPath.parse("/tasks/无摘要").getOrNull()!!, WriteMode.CREATE, content = "x")
                        .copy(summary = null),
                )
            val noKeywords =
                store.write(
                    write(MemoryPath.parse("/tasks/无关键词").getOrNull()!!, WriteMode.CREATE, content = "x")
                        .copy(keywords = emptyList()),
                )

            assertThat((noSummary as Outcome.Err).error.code).isEqualTo(MemoryErrorCodes.INVALID_FIELDS)
            assertThat((noKeywords as Outcome.Err).error.code).isEqualTo(MemoryErrorCodes.INVALID_FIELDS)
        }

    @Test
    public fun `keywords are normalized and deduplicated when stored`(): Unit =
        runBlocking {
            val path = MemoryPath.parse("/people/小红").getOrNull()!!

            val result =
                store.write(
                    write(path, WriteMode.CREATE, content = "同事", source = MemorySource.USER)
                        .copy(keywords = listOf("  张三 ", "ZhangSan", "张三")),
                )

            assertThat((result as Outcome.Ok).value.node.keywords).containsExactly("张三", "zhangsan").inOrder()
        }

    @Test
    public fun `too many keywords are rejected`(): Unit =
        runBlocking {
            val path = MemoryPath.parse("/people/多词").getOrNull()!!
            val tooMany = (1..13).map { "词$it" }

            val result =
                store.write(
                    write(path, WriteMode.CREATE, content = "x", source = MemorySource.USER).copy(keywords = tooMany),
                )

            assertThat((result as Outcome.Err).error.code).isEqualTo(MemoryErrorCodes.INVALID_FIELDS)
        }

    @Test
    public fun `revert of an update restores the previous keywords`(): Unit =
        runBlocking {
            val path = MemoryPath.parse("/tasks/关键词撤销").getOrNull()!!
            store.write(write(path, WriteMode.CREATE, content = "原").copy(keywords = listOf("旧词")))
            val update =
                store.write(write(path, WriteMode.UPDATE, content = "改").copy(keywords = listOf("新词")))

            store.revert((update as Outcome.Ok).value.versionId)

            assertThat(store.get(path)!!.keywords).containsExactly("旧词")
        }

    private fun write(
        path: MemoryPath,
        mode: WriteMode,
        content: String,
        title: String? = null,
        source: MemorySource = MemorySource.AGENT,
    ): MemoryWrite =
        MemoryWrite(
            path = path,
            mode = mode,
            title = title,
            // agent 新建必须带摘要与关键词（ADR-0027），测试默认补齐
            summary = "摘要",
            content = content,
            keywords = listOf(title ?: path.name),
            source = source,
        )
}
