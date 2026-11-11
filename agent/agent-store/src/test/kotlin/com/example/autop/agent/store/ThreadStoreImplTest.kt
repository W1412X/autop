/**
 * ThreadStoreImpl 测试（docs/tech/10 WP6b，ADR-0021）：全局 seq 与父边、可分叉标志、分支标签与标题、
 * 路径可见性、分支删除的可达性回收、大 payload 落文件、preview/lastRunStatus、saveBlob、observe 推送。
 */
package com.example.autop.agent.store

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.Baseline
import com.example.autop.agent.BaselineReason
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.store.db.ThreadsDb
import com.example.autop.agent.store.recall.FtsIndexer
import com.example.autop.agent.store.recall.IndexQueue
import com.example.autop.agent.store.recall.SnippetReader
import com.example.autop.agent.store.testing.NoopLogger
import com.example.autop.agent.store.testing.SeqIdGenerator
import com.example.autop.core.common.RunId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.StopReason
import com.example.autop.llm.Usage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** ThreadStoreImpl 行为测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class ThreadStoreImplTest {
    private lateinit var db: ThreadsDb
    private lateinit var filesDir: File
    private lateinit var queue: IndexQueue
    private lateinit var config: FakeConfigProvider
    private lateinit var clock: FakeClock
    private lateinit var store: ThreadStoreImpl

    @Before
    public fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ThreadsDb::class.java).build()
        filesDir = Files.createTempDirectory("store-test").toFile()
        config = FakeConfigProvider()
        clock = FakeClock(start = 1_000)
        val files = ThreadFiles(filesDir)
        val snippets = SnippetReader(db.eventDao(), files, config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        queue = IndexQueue(setOf(FtsIndexer(db.ftsDao(), snippets)), 500, config, scope, NoopLogger)
        store = ThreadStoreImpl(db, ThreadMappers(files, config), queue, clock, SeqIdGenerator())
    }

    @After
    public fun tearDown() {
        db.close()
        filesDir.deleteRecursively()
    }

    @Test
    public fun `append assigns global seq chained by parentSeq and updates head`(): Unit =
        runBlocking {
            val thread = store.createThread("标题", null)
            val first = store.append(thread, RunId("r1"), userText("你好"))
            clock.advanceBy(10)
            val second = store.append(thread, RunId("r1"), userText("世界"))
            assertThat(second.seq).isGreaterThan(first.seq)
            val row = db.eventDao().bySeq(second.seq)!!
            assertThat(row.parentSeq).isEqualTo(first.seq)
            val head = db.threadDao().byId(thread.value)!!
            assertThat(head.headSeq).isEqualTo(second.seq)
            assertThat(head.updatedAt).isEqualTo(1_010)
            assertThat(head.preview).isEqualTo("世界")
        }

    @Test
    public fun `forkable is set on tool results without unpaired calls only`(): Unit =
        runBlocking {
            val thread = store.createThread("t", null)
            val call = store.append(thread, null, assistantToolCall("c1"))
            val result = store.append(thread, null, toolResult("c1"))
            val user = store.append(thread, null, userText("再问"))
            assertThat(store.forkablePoints(thread)).containsExactly(result.seq)
            assertThat(db.eventDao().bySeq(call.seq)!!.forkable).isFalse()
            assertThat(db.eventDao().bySeq(call.seq)!!.pending).isEqualTo(1)
            assertThat(db.eventDao().bySeq(user.seq)!!.forkable).isFalse()
        }

    @Test
    public fun `branch created at a fork point starts there and gets labels and titles`(): Unit =
        runBlocking {
            val root = store.createThread("买票", null)
            val a = store.append(root, null, userText("一"))
            store.append(root, null, userText("二"))
            val first = store.createThread(null, root to a.seq)
            val second = store.createThread(null, root to a.seq)
            val nested = store.createThread(null, first to a.seq)
            assertThat(db.threadDao().byId(first.value)!!.forkLabel).isEqualTo("1")
            assertThat(db.threadDao().byId(second.value)!!.forkLabel).isEqualTo("2")
            assertThat(db.threadDao().byId(nested.value)!!.forkLabel).isEqualTo("1.1")
            assertThat(store.titleOf(first)).isEqualTo("买票(1)")
            assertThat(store.titleOf(nested)).isEqualTo("买票(1.1)")
            assertThat(db.threadDao().byId(nested.value)!!.rootId).isEqualTo(root.value)
            assertThat(db.threadDao().byId(first.value)!!.headSeq).isEqualTo(a.seq)
        }

    @Test
    public fun `branch history shows ancestors up to the fork point and hides siblings`(): Unit =
        runBlocking {
            val root = store.createThread("根", null)
            val a = store.append(root, null, userText("共享"))
            val b = store.append(root, null, userText("根的后续"))
            val child = store.createThread(null, root to a.seq)
            val own = store.append(child, null, userText("分支内容"))
            val history = store.events(child, 0, Long.MAX_VALUE).map { it.seq }
            assertThat(history).containsExactly(a.seq, own.seq).inOrder()
            assertThat(history).doesNotContain(b.seq)
            assertThat(store.events(root, 0, Long.MAX_VALUE).map { it.seq }).containsExactly(a.seq, b.seq).inOrder()
        }

    @Test
    public fun `eventsSinceLatestBaseline starts at the latest baseline on the path`(): Unit =
        runBlocking {
            val thread = store.createThread(null, null)
            store.append(thread, null, userText("旧"))
            val base = store.append(thread, null, baselineOf("摘要"))
            val latest = store.append(thread, null, userText("新"))
            val since = store.eventsSinceLatestBaseline(thread)
            assertThat(since.map { it.seq }).containsExactly(base.seq, latest.seq).inOrder()
        }

    @Test
    public fun `summary shows preview and last run status`(): Unit =
        runBlocking {
            val thread = store.createThread(null, null)
            store.append(thread, null, userText("x".repeat(100)))
            store.append(thread, null, RunFinished(status = RunOutcome.FAILED, summary = "超时"))
            val summary = store.listThreads(0, 10).single()
            assertThat(summary.preview).hasLength(60)
            assertThat(summary.lastRunStatus).isEqualTo(RunOutcome.FAILED)
        }

    @Test
    public fun `large payload spills to file and decodes back`(): Unit =
        runBlocking {
            config.setOverrides(
                buildJsonObject {
                    put("agent", buildJsonObject { put("store", buildJsonObject { put("inlineMaxChars", 10) }) })
                },
            )
            val thread = store.createThread(null, null)
            val payload = userText("很长的正文".repeat(20))
            val event = store.append(thread, RunId("r1"), payload)
            val row = db.eventDao().bySeq(event.seq)!!
            val reread = store.events(thread, 0, Long.MAX_VALUE).single()
            assertThat(row.payload).startsWith("@file:")
            assertThat(File(filesDir, row.payload.removePrefix("@file:")).exists()).isTrue()
            assertThat(reread.payload).isEqualTo(payload)
        }

    @Test
    public fun `saveBlob writes blob and image dirs with fileRef`(): Unit =
        runBlocking {
            val thread = store.createThread(null, null)
            val blobRef = store.saveBlob(thread, "结果.txt", byteArrayOf(1, 2))
            val imageRef = store.saveBlob(thread, "截图.png", byteArrayOf(3))
            assertThat(blobRef).isEqualTo("threads/${thread.value}/blob/结果.txt")
            assertThat(imageRef).isEqualTo("threads/${thread.value}/img/截图.png")
            assertThat(File(filesDir, imageRef).readBytes()).isEqualTo(byteArrayOf(3))
        }

    @Test
    public fun `deleting a branch keeps shared ancestors and removes its own events`(): Unit =
        runBlocking {
            val root = store.createThread("根", null)
            val shared = store.append(root, null, userText("共享"))
            val child = store.createThread(null, root to shared.seq)
            val mine = store.append(child, null, userText("只属于分支"))
            queue.flush()
            store.delete(child)
            assertThat(db.eventDao().bySeq(shared.seq)).isNotNull()
            assertThat(db.eventDao().bySeq(mine.seq)).isNull()
            assertThat(db.ftsDao().match(root.value, "只属于分支", 10)).isEmpty()
            assertThat(store.events(root, 0, Long.MAX_VALUE).map { it.seq }).containsExactly(shared.seq)
        }

    @Test
    public fun `deleting the last branch removes events indexes and files`(): Unit =
        runBlocking {
            val thread = store.createThread(null, null)
            store.append(thread, null, userText("待删除内容"))
            store.saveBlob(thread, "附件.txt", byteArrayOf(9))
            queue.flush()
            store.delete(thread)
            assertThat(db.eventDao().unreachable()).isEmpty()
            assertThat(db.ftsDao().match(thread.value, "删除", 10)).isEmpty()
            assertThat(File(filesDir, "threads/${thread.value}").exists()).isFalse()
        }

    @Test
    public fun `observe pushes appended events`(): Unit =
        runBlocking {
            val thread = store.createThread(null, null)
            val received = mutableListOf<ThreadEvent>()
            val job = launch { store.observe(thread).toList(received) }
            val event = store.append(thread, null, userText("推送"))
            withTimeout(5_000) {
                while (received.size < 1) {
                    kotlinx.coroutines.yield()
                }
            }
            job.cancel()
            assertThat(received.map { it.seq }).containsExactly(event.seq)
        }

    @Test
    public fun `observeFrom emits the branch path then only its own live events`(): Unit =
        runBlocking {
            val root = store.createThread("根", null)
            val shared = store.append(root, null, userText("共享"))
            val child = store.createThread(null, root to shared.seq)
            val batches = mutableListOf<List<ThreadEvent>>()
            val job = launch { store.observeFrom(child, afterSeq = 0).toList(batches) }
            val producer =
                launch(Dispatchers.Default) {
                    repeat(3) { index -> store.append(child, null, userText("分支$index")) }
                    store.append(root, null, userText("根的新消息"))
                }
            withTimeout(5_000) {
                while (batches.sumOf { it.size } < 4) {
                    kotlinx.coroutines.yield()
                }
            }
            producer.join()
            job.cancel()
            val seqs = batches.flatten().map { it.seq }
            assertThat(seqs.first()).isEqualTo(shared.seq)
            assertThat(seqs.distinct()).hasSize(seqs.size)
            assertThat(batches.flatten().all { it.threadId == child || it.seq == shared.seq }).isTrue()
        }

    @Test
    public fun `observeChanges emits on create append rename and delete`(): Unit =
        runBlocking {
            val received = mutableListOf<Unit>()
            val job = launch { store.observeChanges().toList(received) }
            kotlinx.coroutines.yield()
            val thread = store.createThread(null, null)
            store.append(thread, null, userText("内容"))
            store.rename(thread, "新名字")
            store.delete(thread)
            withTimeout(5_000) {
                while (received.size < 4) {
                    kotlinx.coroutines.yield()
                }
            }
            job.cancel()
            assertThat(received).hasSize(4)
        }

    private fun userText(text: String): UserMessage =
        UserMessage(blocks = listOf(Block.Text(text)), kind = UserMessageKind.INPUT)

    private fun baselineOf(summary: String): Baseline =
        Baseline(
            reason = BaselineReason.COMPACTION,
            messages = listOf(Msg(Role.ASSISTANT, listOf(Block.Text(summary)))),
        )

    private fun assistantToolCall(callId: String): AssistantMessage =
        AssistantMessage(
            blocks = listOf(Block.ToolCall(id = callId, name = "tap", argsJson = "{}")),
            usage = Usage(inputTokens = 1, outputTokens = 1),
            stopReason = StopReason.TOOL_USE,
            modelProfileId = "m1",
        )

    private fun toolResult(callId: String): ToolResultEvent =
        ToolResultEvent(
            callId = callId,
            toolId = "tool.tap",
            ok = true,
            blocks = listOf(Block.Text("ok")),
            durationMs = 5,
        )
}
