/**
 * [RunObserver] 单测（N179）：`ActiveRunInfo.run` 快照跟随 runStore 持久化——
 * ask_user 的 WAITING_USER/waitingQuestion 只写库，不跟随则对话页 AskCard
 * 读旧快照（status 仍是 RUNNING、question 为 null）永不显示。
 */
package com.example.autop.task.impl

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.agent.AgentEvent
import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunHandle
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunState
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.common.getOrNull
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.impl.db.TasksDb
import com.example.autop.task.impl.store.RunStore
import com.example.autop.task.impl.testing.FakeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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

/** 起点（FakeClock 初值）。 */
private const val OBS_START_MS: Long = 10_000L

/** ask 超时（虚拟时间不推进，测试内必被回答）。 */
private const val OBS_ASK_TIMEOUT_MS: Long = 60_000L

/** 协程链泵的轮数（每轮一个真实线程挂起点）。 */
private const val OBS_PUMP_ROUNDS: Int = 24

/** 泵用的不存在 runId（查询无副作用）。 */
private val OBS_PROBE_ID: RunId = RunId("pump-probe")

/** N179：活跃信息快照与 ask_user 等待状态的同步。 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent（TestScope 调度推进）
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class RunObserverTest {
    @Test
    public fun `active record follows run store updates`() =
        runTest {
            val fixture = ObserverFixture()
            val record = fixture.saveRecord()
            val handle = ObserverFakeHandle(record.id)
            fixture.observer.begin(record, handle)
            backgroundScope.launch { fixture.observer.watch(record.id, handle) }
            pumpObserver(fixture)

            fixture.runStore.save(
                record.copy(
                    status = RunStatus.WAITING_USER,
                    waitingQuestion = "继续吗？",
                    waitingOptions = listOf("是", "否"),
                ),
            )
            pumpObserver(fixture)

            val active = fixture.observer.active.value
            assertEquals(RunStatus.WAITING_USER, active?.run?.status)
            assertEquals("继续吗？", active?.run?.waitingQuestion)
            assertEquals(listOf("是", "否"), active?.run?.waitingOptions)
        }

    @Test
    public fun `ask_user waiting state is reflected in active record`() =
        runTest {
            val fixture = ObserverFixture()
            val record = fixture.saveRecord()
            val handle = ObserverFakeHandle(record.id, RunState.WAITING_USER)
            fixture.observer.begin(record, handle)
            backgroundScope.launch { fixture.observer.watch(record.id, handle) }
            pumpObserver(fixture)

            var result: Outcome<String>? = null
            // ask 跑在真实调度器（withTimeoutOrNull 走真实时间，60s 不会在测试内到），
            // 避免 runTest 虚拟时间跳过超时提前收尾（N179 测试坑）。
            val asking =
                launch(Dispatchers.Default) {
                    result = fixture.interaction.ask(record.id, "继续吗？", listOf("是"), OBS_ASK_TIMEOUT_MS)
                }
            pumpObserver(fixture)
            assertNull("ask 应仍在等待（若非如此说明虚拟时间跳了超时）", result)
            val active = fixture.observer.active.value
            assertEquals(RunStatus.WAITING_USER, active?.run?.status)
            assertEquals("继续吗？", active?.run?.waitingQuestion)

            fixture.interaction.answer(record.id, "好的")
            pumpObserver(fixture)
            asking.join()
            assertEquals("好的", result?.getOrNull())
            val updated = fixture.observer.active.value
            assertEquals(RunStatus.RUNNING, updated?.run?.status)
            assertNull(updated?.run?.waitingQuestion)
        }
}

/** 测试装配：真实 RunStore/RunObserver/ChatUserInteraction + 可控句柄。 */
private class ObserverFixture {
    val context: Context = ApplicationProvider.getApplicationContext()
    val clock = FakeClock(start = OBS_START_MS)
    val config = FakeConfigProvider()
    val db: TasksDb =
        Room.inMemoryDatabaseBuilder(context, TasksDb::class.java).allowMainThreadQueries().build()
    val runStore = RunStore(db)
    val observer = RunObserver(runStore, config, clock, FakeFlowProgressFeed)
    val interaction = ChatUserInteraction(runStore, FakeLogger)

    suspend fun saveRecord(): RunRecord {
        val record =
            RunRecord(
                id = RunId("run-1"),
                taskId = null,
                threadId = ThreadId("thread-1"),
                origin = RunOrigin.CHAT,
                status = RunStatus.RUNNING,
                triggerContext = emptyMap(),
                createdAt = clock.nowMillis(),
                startedAt = clock.nowMillis(),
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

/** 可控状态的运行句柄。 */
private class ObserverFakeHandle(
    override val runId: RunId,
    initial: RunState = RunState.STARTING,
) : RunHandle {
    override val events: SharedFlow<AgentEvent> = MutableSharedFlow()

    override val state: StateFlow<RunState> = MutableStateFlow(initial)

    override fun steer(text: String): Unit = Unit

    override fun pause(
        reason: PauseReason,
        detail: String?,
    ): Unit = Unit

    override fun resume(note: String?): Unit = Unit

    override fun cancel(): Unit = Unit

    override suspend fun await(): RunFinished = RunFinished(status = RunOutcome.SUCCEEDED, summary = "ok")
}

/**
 * 泵协程链（docs/tech/07 §9 测试基建）：Room 真实线程挂起点让出后，
 * 用真实 suspend 查询把恢复续体推进虚拟调度队列。
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent（TestScope 调度推进）
private suspend fun TestScope.pumpObserver(fixture: ObserverFixture) {
    repeat(OBS_PUMP_ROUNDS) {
        runCurrent()
        fixture.runStore.get(OBS_PROBE_ID)
    }
}
