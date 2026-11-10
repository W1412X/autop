/**
 * [ChatUserInteraction] ask 收口写回（暂停翻回执行态修复）：回答/超时只清等待字段；
 * 期间被暂停的 Run 不翻回 RUNNING。
 */
package com.example.autop.task.impl

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.impl.db.TasksDb
import com.example.autop.task.impl.store.RunStore
import com.example.autop.task.impl.testing.FakeLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ask 等待超时（虚拟时钟内不会走到）。 */
private const val ASK_TIMEOUT_MS: Long = 60_000L

/** 等 ask 落库的泵询轮数与间隔（毫秒）：Room 真实异步，逐轮泵调度并查询。 */
private const val ASK_PUMP_ROUNDS: Int = 200
private const val ASK_PUMP_STEP_MS: Long = 5L

/** ask 收口状态写回的行为矩阵。 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent（TestScope 调度推进）
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class ChatUserInteractionTest {
    @Test
    public fun `answer flips WAITING_USER back to RUNNING and clears question`() =
        runTest {
            val fixture = InteractionFixture()
            val record = fixture.saved(RunStatus.QUEUED)
            val ask = launch { fixture.interaction.ask(record.id, "继续吗", emptyList(), ASK_TIMEOUT_MS) }
            awaitWaiting(fixture, record.id)
            assertEquals(RunStatus.WAITING_USER, fixture.runStore.get(record.id)?.status)

            fixture.interaction.answer(record.id, "好")
            ask.join()

            val after = fixture.runStore.get(record.id)
            assertEquals(RunStatus.RUNNING, after?.status)
            assertNull(after?.waitingQuestion)
        }

    @Test
    public fun `answer after pause keeps PAUSED`() =
        runTest {
            val fixture = InteractionFixture()
            val record = fixture.saved(RunStatus.QUEUED)
            val ask = launch { fixture.interaction.ask(record.id, "继续吗", emptyList(), ASK_TIMEOUT_MS) }
            awaitWaiting(fixture, record.id)
            // 暂停落在 ask 等待中（RunPaused 事件把状态写成 PAUSED）。
            fixture.runStore.mutate(record.id) { it.copy(status = RunStatus.PAUSED) }

            fixture.interaction.answer(record.id, "好")
            ask.join()

            val after = fixture.runStore.get(record.id)
            assertEquals(RunStatus.PAUSED, after?.status)
            assertNull(after?.waitingQuestion)
        }
}

/** 等 ask 将 WAITING_USER 落库（Room 真实异步：逐轮泵调度并查询，超轮数由断言报出）。 */
private suspend fun TestScope.awaitWaiting(
    fixture: InteractionFixture,
    runId: RunId,
) {
    repeat(ASK_PUMP_ROUNDS) {
        if (fixture.runStore.get(runId)?.status == RunStatus.WAITING_USER) return
        runCurrent()
        Thread.sleep(ASK_PUMP_STEP_MS)
    }
}

/** 测试装配：真实 RunStore + ChatUserInteraction。 */
private class InteractionFixture {
    val runStore: RunStore =
        RunStore(
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    TasksDb::class.java,
                ).allowMainThreadQueries()
                .build(),
        )
    val interaction = ChatUserInteraction(runStore, FakeLogger)

    suspend fun saved(status: RunStatus): RunRecord {
        val record =
            RunRecord(
                id = RunId("run-1"),
                taskId = null,
                threadId = ThreadId("thread-1"),
                origin = RunOrigin.CHAT,
                status = status,
                triggerContext = emptyMap(),
                createdAt = 0L,
                startedAt = null,
                endedAt = null,
                summary = null,
                errorCode = null,
                pauseReason = null,
                waitingQuestion = null,
            )
        runStore.save(record)
        return record
    }
}
