/**
 * [InterruptedRunRecovery] 单测（docs/tech/04 §3.11，07 §1.3，WP8.4）：启动收口全部
 * 未结束 Run（已启动 → INTERRUPTED，未启动 → CANCELLED/LOST_ON_RESTART）并补写带
 * liveNote 的 RunFinished；“继续”在同一 Thread 新起 RESUME Run（input 为
 * agent.resume.after_interrupt 模板文本）；非 INTERRUPTED 与不存在的 Run 拒绝。
 */
package com.example.autop.task.impl

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.TaskId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.common.UuidV7IdGenerator
import com.example.autop.core.config.TaskQueueSection
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.device.DeviceState
import com.example.autop.task.ChatTitleGenerator
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.core.RunQueue
import com.example.autop.task.impl.db.TasksDb
import com.example.autop.task.impl.notify.UserNotifierImpl
import com.example.autop.task.impl.reminder.LockReminder
import com.example.autop.task.impl.store.RunStore
import com.example.autop.task.impl.testing.FakeAccessibilityStatus
import com.example.autop.task.impl.testing.FakeAgentSession
import com.example.autop.task.impl.testing.FakeAppLauncher
import com.example.autop.task.impl.testing.FakeDeviceStateProvider
import com.example.autop.task.impl.testing.FakeLogger
import com.example.autop.task.impl.testing.FakeScreenLease
import com.example.autop.task.impl.testing.FakeTaskRepository
import com.example.autop.task.impl.testing.FakeThreadStore
import com.example.autop.task.impl.testing.FakeTouchShield
import com.example.autop.task.impl.testing.FakeUserActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 时钟起点（FakeClock 初值）。 */
private const val RECOVERY_START_MS: Long = 10_000L

/** 标题生成空实现（恢复流程不新建对话，不触发标题生成）。 */
private object NoopTitleGenerator : ChatTitleGenerator {
    override suspend fun generate(firstMessage: String): String? = null
}

/** 中断恢复行为（docs/tech/04 §3.11）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class InterruptedRunRecoveryTest {
    @Test
    public fun `started run becomes interrupted`() =
        runTest {
            val fixture = RecoveryFixture(backgroundScope)
            fixture.saveRun("run-1", RunStatus.RUNNING, started = true)

            val count = fixture.recovery.closeUnfinished()

            assertEquals(1, count)
            val record = fixture.runStore.get(RunId("run-1"))
            assertEquals(RunStatus.INTERRUPTED, record?.status)
            assertNotNull(record?.endedAt)
            val finished =
                fixture.threads.appended
                    .single()
                    .payload as RunFinished
            assertEquals(RunOutcome.INTERRUPTED, finished.status)
            assertNotNull(finished.liveNote)
        }

    @Test
    public fun `queued run becomes cancelled lost_on_restart`() =
        runTest {
            val fixture = RecoveryFixture(backgroundScope)
            fixture.saveRun("run-1", RunStatus.QUEUED, started = false)

            fixture.recovery.closeUnfinished()

            val record = fixture.runStore.get(RunId("run-1"))
            assertEquals(RunStatus.CANCELLED, record?.status)
            assertEquals(TaskErrorCodes.LOST_ON_RESTART, record?.errorCode)
            val finished =
                fixture.threads.appended
                    .single()
                    .payload as RunFinished
            assertEquals(RunOutcome.CANCELLED, finished.status)
            assertEquals(TaskErrorCodes.LOST_ON_RESTART, finished.errorCode)
            assertNotNull(finished.liveNote)
        }

    @Test
    public fun `paused and waiting runs are closed`() =
        runTest {
            val fixture = RecoveryFixture(backgroundScope)
            fixture.saveRun("run-paused", RunStatus.PAUSED, started = true)
            fixture.saveRun("run-waiting", RunStatus.WAITING_USER, started = false)

            val count = fixture.recovery.closeUnfinished()

            assertEquals(2, count)
            assertEquals(RunStatus.INTERRUPTED, fixture.runStore.get(RunId("run-paused"))?.status)
            val waiting = fixture.runStore.get(RunId("run-waiting"))
            assertEquals(RunStatus.CANCELLED, waiting?.status)
            assertEquals(TaskErrorCodes.LOST_ON_RESTART, waiting?.errorCode)
            assertEquals(2, fixture.threads.appended.size)
        }

    @Test
    public fun `中断的 Run 点继续时同线程新起 Run`() =
        runTest {
            val fixture = RecoveryFixture(backgroundScope)
            val source = fixture.saveRun("run-1", RunStatus.RUNNING, started = true)
            fixture.recovery.closeUnfinished()

            val result = fixture.recovery.resume(source.id)

            assertTrue(result is Outcome.Ok)
            val resumed = (result as Outcome.Ok).value
            assertEquals(RunOrigin.RESUME, resumed.origin)
            assertEquals(source.threadId, resumed.threadId)
            assertEquals("run-1", resumed.triggerContext[RunExecutor.RESUMED_FROM_KEY])
        }

    @Test
    public fun `非 INTERRUPTED 状态拒绝恢复`() =
        runTest {
            val fixture = RecoveryFixture(backgroundScope)
            val source = fixture.saveRun("run-1", RunStatus.RUNNING, started = true)

            val result = fixture.recovery.resume(source.id)

            assertTrue(result is Outcome.Err)
            assertEquals(TaskErrorCodes.INVALID, (result as Outcome.Err).error.code)
        }

    @Test
    public fun `找不到 Run 返回 NOT_FOUND`() =
        runTest {
            val fixture = RecoveryFixture(backgroundScope)

            val result = fixture.recovery.resume(RunId("missing"))

            assertTrue(result is Outcome.Err)
            assertEquals(TaskErrorCodes.NOT_FOUND, (result as Outcome.Err).error.code)
        }
}

/** 测试装配：真实 RunStore/RunExecutor + fake 端口（docs/tech/07 §9）。 */
private class RecoveryFixture(
    scope: CoroutineScope,
) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(start = RECOVERY_START_MS)
    private val config = FakeConfigProvider()
    private val db: TasksDb =
        Room.inMemoryDatabaseBuilder(context, TasksDb::class.java).allowMainThreadQueries().build()
    val runStore = RunStore(db)
    val threads = FakeThreadStore()
    private val pending = PendingRuns()
    private val screenLease = FakeScreenLease()
    private val scheduler =
        RunScheduler(
            runStore = runStore,
            queue = RunQueue(TaskQueueSection.DEFAULT_ORIGIN_WEIGHTS),
            pending = pending,
            runner =
                RunRunner(
                    agentSession = FakeAgentSession(),
                    flowSession = FakeFlowSession,
                    flowStore = FakeFlowStore,
                    flowFeed = FakeFlowProgressFeed,
                    runStore = runStore,
                    pending = pending,
                    observer = RunObserver(runStore, config, clock, FakeFlowProgressFeed),
                    touchShieldWatcher = TouchShieldWatcher(FakeTouchShield()),
                    screenLease = screenLease,
                    deviceState = FakeDeviceStateProvider(recoveryUnlockedState()),
                    appLauncher = FakeAppLauncher(),
                    notifier = UserNotifierImpl(context, config),
                    taskStore = FakeTaskRepository(),
                    threadStore = threads,
                    configProvider = config,
                    prompts = FakePromptRepository(),
                    clock = clock,
                    scope = scope,
                    logger = FakeLogger,
                ),
            screenLease = screenLease,
            accessibilityStatus = FakeAccessibilityStatus(),
            deviceState = FakeDeviceStateProvider(recoveryUnlockedState()),
            userActivity = FakeUserActivity(),
            lockReminder = LockReminder(context, config, clock),
            notifier = UserNotifierImpl(context, config),
            configProvider = config,
            threadStore = threads,
            prompts = FakePromptRepository(),
            clock = clock,
            scope = scope,
            logger = FakeLogger,
        )
    private val executor =
        RunExecutor(
            agentSession = FakeAgentSession(),
            threadStore = threads,
            runStore = runStore,
            pending = pending,
            scheduler = scheduler,
            titleGenerator = NoopTitleGenerator,
            ids = UuidV7IdGenerator(clock),
            clock = clock,
            logger = FakeLogger,
            scope = scope,
            configProvider = config,
        )
    val recovery =
        InterruptedRunRecovery(
            runStore = runStore,
            threadStore = threads,
            prompts = FakePromptRepository(),
            executor = executor,
            clock = clock,
            logger = FakeLogger,
        )

    /** 落一条指定状态的 Run（[started] 决定 startedAt）。 */
    suspend fun saveRun(
        id: String,
        status: RunStatus,
        started: Boolean,
    ): RunRecord {
        val record =
            RunRecord(
                id = RunId(id),
                taskId = TaskId("t1"),
                threadId = ThreadId("thread-$id"),
                origin = RunOrigin.TASK_MANUAL,
                status = status,
                triggerContext = emptyMap(),
                createdAt = RECOVERY_START_MS,
                startedAt = if (started) RECOVERY_START_MS else null,
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

/** 未锁屏的设备状态。 */
private fun recoveryUnlockedState(): DeviceState =
    DeviceState(
        batteryPercent = 80,
        charging = false,
        networkType = "wifi",
        screenOn = true,
        keyguardLocked = false,
        foregroundPackage = null,
    )
