/**
 * [RunExecutor] 入队落盘单测（agent-harness-overhaul WP8.1）：用户输入入队即写入
 * 事件日志（chat=INPUT、resume=INJECTED），任务 Run 不写输入（目标只由 GoalContributor 注入）。
 */
package com.example.autop.task.impl

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.agent.EventPayload
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
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
import com.example.autop.llm.Block
import com.example.autop.task.ChatTitleGenerator
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.Task
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 时钟起点（FakeClock 初值）。 */
private const val EXEC_START_MS: Long = 10_000L

/** 入队落盘行为（WP8.1）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class RunExecutorTest {
    @Test
    public fun `chat input is persisted before scheduling`() =
        runTest {
            val fixture = ExecutorFixture(backgroundScope)

            val result = fixture.executor.launchChat(null, "帮我发消息", images = emptyList())

            val record = (result as Outcome.Ok).value
            val messages = fixture.threads.payloads().filterIsInstance<UserMessage>()
            assertEquals(1, messages.size)
            assertEquals(UserMessageKind.INPUT, messages.single().kind)
            assertEquals("帮我发消息", (messages.single().blocks.single() as Block.Text).text)
            // 入队即落盘：调度尚未执行 Run，用户原话已经在事件日志里。
            assertTrue(fixture.session.requests.isEmpty())
            assertEquals(
                record.threadId,
                fixture.threads.appended
                    .single()
                    .threadId,
            )
        }

    @Test
    public fun `task run writes no input message`() =
        runTest {
            val fixture = ExecutorFixture(backgroundScope)

            fixture.executor.launchTask(taskOf("t1"), RunOrigin.TASK_MANUAL, emptyMap())

            assertTrue(fixture.threads.payloads().none { it is UserMessage })
        }

    @Test
    public fun `resume input is injected kind`() =
        runTest {
            val fixture = ExecutorFixture(backgroundScope)
            val source =
                RunRecord(
                    id = RunId("src"),
                    taskId = null,
                    threadId = ThreadId("thread-src"),
                    origin = RunOrigin.CHAT,
                    status = RunStatus.INTERRUPTED,
                    triggerContext = emptyMap(),
                    createdAt = EXEC_START_MS,
                    startedAt = EXEC_START_MS,
                    endedAt = EXEC_START_MS,
                    summary = null,
                    errorCode = null,
                    pauseReason = null,
                    waitingQuestion = null,
                )

            fixture.executor.launchResume(source, "继续做完")

            val message =
                fixture.threads
                    .payloads()
                    .filterIsInstance<UserMessage>()
                    .single()
            assertEquals(UserMessageKind.INJECTED, message.kind)
        }
}

/** append 事件的载荷（断言用）。 */
private fun FakeThreadStore.payloads(): List<EventPayload> = appended.map { it.payload }

/** 测试装配：真实 RunStore/RunScheduler + fake 端口；无障碍未连接挡住执行，专注入队行为。 */
private class ExecutorFixture(
    scope: CoroutineScope,
) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(start = EXEC_START_MS)
    private val config = FakeConfigProvider()
    private val db: TasksDb =
        Room.inMemoryDatabaseBuilder(context, TasksDb::class.java).allowMainThreadQueries().build()
    private val runStore = RunStore(db)
    val threads = FakeThreadStore()
    private val pending = PendingRuns()
    val session = FakeAgentSession()
    private val scheduler =
        RunScheduler(
            runStore = runStore,
            queue = RunQueue(TaskQueueSection.DEFAULT_ORIGIN_WEIGHTS),
            pending = pending,
            runner =
                RunRunner(
                    agentSession = session,
                    flowSession = FakeFlowSession,
                    flowStore = FakeFlowStore,
                    flowFeed = FakeFlowProgressFeed,
                    runStore = runStore,
                    pending = pending,
                    observer = RunObserver(runStore, config, clock, FakeFlowProgressFeed),
                    touchShieldWatcher = TouchShieldWatcher(FakeTouchShield()),
                    screenLease = FakeScreenLease(),
                    deviceState = FakeDeviceStateProvider(execUnlockedState()),
                    appLauncher = FakeAppLauncher(),
                    notifier = UserNotifierImpl(context, config),
                    taskStore = FakeTaskRepository(),
                    threadStore = threads,
                    experience = FakeExperienceExtractor(),
                    configProvider = config,
                    prompts = FakePromptRepository(),
                    clock = clock,
                    scope = scope,
                    logger = FakeLogger,
                ),
            screenLease = FakeScreenLease(),
            accessibilityStatus = FakeAccessibilityStatus(initial = false),
            deviceState = FakeDeviceStateProvider(execUnlockedState()),
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

    val executor =
        RunExecutor(
            agentSession = session,
            threadStore = threads,
            runStore = runStore,
            pending = pending,
            scheduler = scheduler,
            titleGenerator = NoopExecTitleGenerator,
            ids = UuidV7IdGenerator(clock),
            clock = clock,
            configProvider = config,
            logger = FakeLogger,
            scope = scope,
        )
}

/** 标题生成空实现。 */
private object NoopExecTitleGenerator : ChatTitleGenerator {
    override suspend fun generate(firstMessage: String): String? = null
}

/** 调度用任务。 */
private fun taskOf(id: String): Task =
    Task(
        id = TaskId(id),
        name = "task-$id",
        goal = "goal",
        trigger = Trigger.Manual,
        createdAt = EXEC_START_MS,
        updatedAt = EXEC_START_MS,
    )

/** 未锁屏的设备状态。 */
private fun execUnlockedState(): DeviceState =
    DeviceState(
        batteryPercent = 80,
        charging = false,
        networkType = "wifi",
        screenOn = true,
        keyguardLocked = false,
        foregroundPackage = null,
    )
