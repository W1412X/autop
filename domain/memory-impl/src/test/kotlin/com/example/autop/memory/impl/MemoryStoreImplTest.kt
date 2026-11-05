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
            content = content,
            source = source,
        )
}
