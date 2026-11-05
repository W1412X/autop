/**
 * ThreadStoreImpl 测试（docs/tech/10 WP6b）：seq 连续、Baseline 指针、
 * 大 payload 落文件、preview/lastRunStatus、saveBlob、delete、forkablePoints、
 * observe / observeChanges 推送。
 */
package com.example.autop.agent.store

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.Baseline
import com.example.autop.agent.BaselineReason
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
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
    public fun `append assigns consecutive seq and updates thread row`(): Unit =
        runBlocking {
            val thread = store.createThread("标题", null)

            val first = store.append(thread, RunId("r1"), userText("你好"))
            clock.advanceBy(10)
            val second = store.append(thread, RunId("r1"), userText("世界"))

            assertThat(first.seq).isEqualTo(1)
            assertThat(second.seq).isEqualTo(2)
            val row = db.threadDao().byId(thread.value)!!
            assertThat(row.lastSeq).isEqualTo(2)
            assertThat(row.updatedAt).isEqualTo(1_010)
            assertThat(row.preview).isEqualTo("世界")
        }

    @Test
    public fun `eventsSinceLatestBaseline starts at the latest baseline`(): Unit =
        runBlocking {
            val thread = store.createThread(null, null)
            store.append(thread, null, userText("一"))
            val baseline = store.append(thread, null, baselineOf("摘要"))
            store.append(thread, null, userText("二"))

            val events = store.eventsSinceLatestBaseline(thread)

            assertThat(events.map { it.seq }).containsExactly(baseline.seq, 3L).inOrder()
        }

    @Test
    public fun `eventsSinceLatestBaseline returns all events when no baseline`(): Unit =
        runBlocking {
            val thread = store.createThread(null, null)
            store.append(thread, null, userText("一"))
            store.append(thread, null, userText("二"))

            val events = store.eventsSinceLatestBaseline(thread)

            assertThat(events.map { it.seq }).containsExactly(1L, 2L).inOrder()
        }

    @Test
    public fun `summary shows preview and last run status`(): Unit =
        runBlocking {
            val thread = store.createThread(null, null)
            store.append(thread, null, userText("x".repeat(100)))
            store.append(
                thread,
                null,
                RunFinished(status = RunOutcome.FAILED, summary = "超时"),
            )

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
            val row = db.eventDao().bySeq(thread.value, event.seq)!!
            val reread = store.events(thread, 0, Long.MAX_VALUE).single()

            assertThat(row.payload).startsWith("@file:")
            assertThat(
                File(filesDir, row.payload.removePrefix("@file:")).exists(),
            ).isTrue()
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
    public fun `delete removes events indexes and files`(): Unit =
        runBlocking {
            val thread = store.createThread(null, null)
            store.append(thread, null, userText("待删除内容"))
            store.saveBlob(thread, "附件.txt", byteArrayOf(9))
            queue.flush()

            store.delete(thread)

            assertThat(db.eventDao().range(thread.value, 0, Long.MAX_VALUE)).isEmpty()
            assertThat(db.ftsDao().match(thread.value, "删除", 10)).isEmpty()
            assertThat(File(filesDir, "threads/${thread.value}").exists()).isFalse()
        }

    @Test
    public fun `forkablePoints marks batch ends without unpaired tool calls`(): Unit =
        runBlocking {
            val thread = store.createThread(null, null)
            store.append(thread, null, assistantToolCall("c1"))
            store.append(thread, null, toolResult("c1"))
            store.append(thread, null, userText("再问"))
            store.append(thread, null, assistantToolCall("c2"))
            store.append(thread, null, userText("打断"))

            val points = store.forkablePoints(thread)

            assertThat(points).containsExactly(2L).inOrder()
        }

    @Test
    public fun `observe pushes appended events`(): Unit =
        runBlocking {
            val thread = store.createThread(null, null)
            val received = mutableListOf<com.example.autop.agent.ThreadEvent>()
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
