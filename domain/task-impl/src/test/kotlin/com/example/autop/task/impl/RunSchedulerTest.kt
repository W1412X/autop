/**
 * [RunScheduler] 单测（docs/tech/07 §9）：排队顺序、租约占用时等待释放后继续、
 * 锁屏提醒/跳过、最近触摸延后、needsScreen=false 不取租约。
 */
package com.example.autop.task.impl

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.core.common.RunId
import com.example.autop.core.common.TaskId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.TaskQueueSection
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.device.DeviceState
import com.example.autop.task.LockedBehavior
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.Task
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.Trigger
import com.example.autop.task.core.RunQueue
import com.example.autop.task.impl.db.TasksDb
import com.example.autop.task.impl.notify.UserNotifierImpl
import com.example.autop.task.impl.reminder.LockReminder
import com.example.autop.task.impl.store.RunStore
import com.example.autop.task.impl.testing.FakeAccessibilityStatus
import com.example.autop.task.impl.testing.FakeAgentSession
import com.example.autop.task.impl.testing.FakeAppLauncher
import com.example.autop.task.impl.testing.FakeDeviceStateProvider
import com.example.autop.task.impl.testing.FakeExperienceExtractor
import com.example.autop.task.impl.testing.FakeLogger
import com.example.autop.task.impl.testing.FakeScreenLease
import com.example.autop.task.impl.testing.FakeTaskRepository
import com.example.autop.task.impl.testing.FakeThreadStore
import com.example.autop.task.impl.testing.FakeTouchShield
import com.example.autop.task.impl.testing.FakeUserActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 调度起点（FakeClock 初值）。 */
private const val START_MS: Long = 10_000L

/** 用例里“最近触摸”距起点的时长（剩 5s 静默期）。 */
private const val TOUCH_AGO_MS: Long = 5_000L

/** idle 静默期（`task.idleBeforeStartMs` 默认 10s）的两个推进段。 */
private const val PARTIAL_IDLE_MS: Long = 4_000L

/** idle 静默期的第二推进段（总和大于剩余静默期）。 */
private const val REST_IDLE_MS: Long = 2_000L

/** 协程链泵的轮数（每轮一个真实线程挂起点）。 */
private const val PUMP_ROUNDS: Int = 12

/** 泵用的不存在 runId（查询无副作用）。 */
private val PUMP_PROBE_ID: RunId = RunId("pump-probe")

/** 07 §9 的 RunCoordinator 行为矩阵。 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy/runCurrent（TestScope 调度推进）
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class RunSchedulerTest {
    @Test
    public fun `higher origin weight is scheduled first`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            val condition = fixture.enqueueRun("run-c", RunOrigin.CONDITION, taskOf("t1"))
            val scheduled = fixture.enqueueRun("run-s", RunOrigin.SCHEDULE, taskOf("t2"))
            fixture.scheduler.trySchedule()
            pump(fixture)
            assertEquals(listOf(scheduled.id, condition.id), fixture.session.requests.map { it.runId })
        }

    @Test
    public fun `busy lease waits until released`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.screenLease.setHolder("other-owner")
            val record = fixture.enqueueRun("run-1", RunOrigin.SCHEDULE, taskOf("t1"))
            fixture.scheduler.trySchedule()
            pump(fixture)
            assertTrue(fixture.session.requests.isEmpty())
            assertEquals(RunStatus.WAITING_SCREEN, fixture.runStore.get(record.id)?.status)
            assertEquals(1, fixture.queue.size)
            fixture.screenLease.setHolder(null)
            pump(fixture)
            assertEquals(listOf(record.id), fixture.session.requests.map { it.runId })
        }

    @Test
    public fun `locked screen reminds and waits for user`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.devices.state = unlockedState().copy(keyguardLocked = true)
            val record = fixture.enqueueRun("run-1", RunOrigin.SCHEDULE, taskOf("t1", locked = LockedBehavior.REMIND))
            fixture.scheduler.trySchedule()
            pump(fixture)
            assertTrue(fixture.session.requests.isEmpty())
            assertEquals(RunStatus.WAITING_USER, fixture.runStore.get(record.id)?.status)
            assertTrue(shadowOf(fixture.manager).allNotifications.isNotEmpty())
        }

    @Test
    public fun `locked screen with skip behavior cancels the run`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.devices.state = unlockedState().copy(keyguardLocked = true)
            val record = fixture.enqueueRun("run-1", RunOrigin.SCHEDULE, taskOf("t1", locked = LockedBehavior.SKIP))
            fixture.scheduler.trySchedule()
            pump(fixture)
            val closed = fixture.runStore.get(record.id)
            assertEquals(RunStatus.CANCELLED, closed?.status)
            assertEquals(TaskErrorCodes.LOCKED, closed?.errorCode)
            assertEquals(0, fixture.queue.size)
        }

    @Test
    public fun `recent user touch delays the start`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.activity.lastTouchAt = START_MS - TOUCH_AGO_MS
            fixture.enqueueRun("run-1", RunOrigin.SCHEDULE, taskOf("t1"))
            fixture.scheduler.trySchedule()
            runCurrent()
            assertTrue(fixture.session.requests.isEmpty())
            tick(fixture, PARTIAL_IDLE_MS)
            assertTrue(fixture.session.requests.isEmpty())
            tick(fixture, REST_IDLE_MS)
            assertEquals(1, fixture.session.requests.size)
        }

    @Test
    public fun `background run takes no lease and ignores the lock`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.devices.state = unlockedState().copy(keyguardLocked = true)
            fixture.enqueueRun("run-1", RunOrigin.SCHEDULE, taskOf("t1", needsScreen = false))
            fixture.scheduler.trySchedule()
            pump(fixture)
            assertEquals(1, fixture.session.requests.size)
            assertEquals(0, fixture.screenLease.leaseCalls)
        }
}

/** 测试装配：真实 RunStore/RunRunner/LockReminder + fake 设备端口（docs/tech/07 §9）。 */
private class Fixture(
    scope: CoroutineScope,
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val clock = FakeClock(start = START_MS)
    val config = FakeConfigProvider()
    val db: TasksDb =
        Room.inMemoryDatabaseBuilder(context, TasksDb::class.java).allowMainThreadQueries().build()
    val runStore = RunStore(db)
    val pending = PendingRuns()
    val queue = RunQueue(TaskQueueSection.DEFAULT_ORIGIN_WEIGHTS)
    val screenLease = FakeScreenLease()
    val accessibility = FakeAccessibilityStatus()
    val devices = FakeDeviceStateProvider(unlockedState())
    val activity = FakeUserActivity()
    val launcher = FakeAppLauncher()
    val session = FakeAgentSession()
    val manager: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notifier = UserNotifierImpl(context, config)
    val runner =
        RunRunner(
            agentSession = session,
            runStore = runStore,
            pending = pending,
            observer = RunObserver(runStore, config, clock),
            touchShieldWatcher = TouchShieldWatcher(FakeTouchShield()),
            screenLease = screenLease,
            deviceState = devices,
            appLauncher = launcher,
            notifier = notifier,
            taskStore = FakeTaskRepository(),
            threadStore = FakeThreadStore(),
            experience = FakeExperienceExtractor(),
            configProvider = config,
            clock = clock,
            scope = scope,
            logger = FakeLogger,
        )
    val scheduler =
        RunScheduler(
            runStore = runStore,
            queue = queue,
            pending = pending,
            runner = runner,
            screenLease = screenLease,
            accessibilityStatus = accessibility,
            deviceState = devices,
            userActivity = activity,
            lockReminder = LockReminder(context, config, clock),
            notifier = notifier,
            configProvider = config,
            clock = clock,
            scope = scope,
            logger = FakeLogger,
        )

    suspend fun enqueueRun(
        id: String,
        origin: RunOrigin,
        task: Task,
    ): RunRecord {
        val record =
            RunRecord(
                id = RunId(id),
                taskId = task.id,
                threadId = ThreadId("thread-$id"),
                origin = origin,
                status = RunStatus.QUEUED,
                triggerContext = emptyMap(),
                createdAt = clock.nowMillis(),
                startedAt = null,
                endedAt = null,
                summary = null,
                errorCode = null,
                pauseReason = null,
                waitingQuestion = null,
            )
        runStore.save(record)
        pending.put(record.id, PendingEntry(input = emptyList(), vars = emptyMap(), task = task))
        scheduler.enqueue(record)
        return record
    }
}

/** 未锁屏的设备状态。 */
private fun unlockedState(): DeviceState =
    DeviceState(
        batteryPercent = 80,
        charging = false,
        networkType = "wifi",
        screenOn = true,
        keyguardLocked = false,
        foregroundPackage = null,
    )

/** 调度用任务。 */
private fun taskOf(
    id: String,
    needsScreen: Boolean = true,
    locked: LockedBehavior = LockedBehavior.REMIND,
): Task =
    Task(
        id = TaskId(id),
        name = "task-$id",
        goal = "goal",
        trigger = Trigger.Manual,
        needsScreen = needsScreen,
        lockedBehavior = locked,
        createdAt = START_MS,
        updatedAt = START_MS,
    )

/** 虚拟时间与 FakeClock 同步推进并泵协程链（idle 延后用例）。 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy（TestScope 调度推进）
private suspend fun TestScope.tick(
    fixture: Fixture,
    ms: Long,
) {
    fixture.clock.advanceBy(ms)
    advanceTimeBy(ms)
    pump(fixture)
}

/**
 * 泵协程链（docs/tech/07 §9 测试基建）：调度链在 Room 真实线程挂起点让出，
 * `advanceUntilIdle` 不等真实线程的恢复；每轮用一次真实 suspend 查询作泵，
 * 让查询执行器完成的恢复进入虚拟调度队列并被执行。
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent（TestScope 调度推进）
private suspend fun TestScope.pump(
    fixture: Fixture,
    rounds: Int = PUMP_ROUNDS,
) {
    repeat(rounds) {
        runCurrent()
        fixture.runStore.get(PUMP_PROBE_ID)
    }
}
