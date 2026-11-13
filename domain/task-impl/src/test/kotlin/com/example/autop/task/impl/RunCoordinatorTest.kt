/**
 * [RunCoordinator] steer 单测（agent-harness-overhaul WP8.5）：排队中（未启动）的 Run
 * 直接落盘 STEER 用户消息；已结束的 Run 返回 `task.invalid`，不写任何事件。
 */
package com.example.autop.task.impl

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowIssue
import com.example.autop.flow.FlowValidationService
import com.example.autop.task.ChatTitleGenerator
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.core.RunQueue
import com.example.autop.task.impl.db.TasksDb
import com.example.autop.task.impl.host.AgentHostStarter
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
private const val STEER_START_MS: Long = 10_000L

/** steer 落盘与拒绝行为（WP8.5）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class RunCoordinatorTest {
    @Test
    public fun `steer on queued run appends steer message`() =
        runTest {
            val fixture = CoordinatorFixture(backgroundScope)
            fixture.saveRun("run-1", RunStatus.QUEUED)

            val result = fixture.coordinator.steer(RunId("run-1"), "改用方案 B")

            assertTrue(result is Outcome.Ok)
            val message =
                fixture.threads.appended
                    .single()
                    .payload as UserMessage
            assertEquals(UserMessageKind.STEER, message.kind)
            assertEquals("改用方案 B", (message.blocks.single() as com.example.autop.llm.Block.Text).text)
        }

    @Test
    public fun `steer on finished run returns error`() =
        runTest {
            val fixture = CoordinatorFixture(backgroundScope)
            fixture.saveRun("run-1", RunStatus.SUCCEEDED)

            val result = fixture.coordinator.steer(RunId("run-1"), "改用方案 B")

            assertTrue(result is Outcome.Err)
            assertEquals(TaskErrorCodes.INVALID, (result as Outcome.Err).error.code)
            assertTrue(fixture.threads.appended.isEmpty())
        }
}

/** 测试装配：真实 RunStore/RunExecutor 链 + fake 端口（docs/tech/07 §9 同款）。 */
private class CoordinatorFixture(
    scope: CoroutineScope,
) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(start = STEER_START_MS)
    private val config = FakeConfigProvider()
    private val db: TasksDb =
        Room.inMemoryDatabaseBuilder(context, TasksDb::class.java).allowMainThreadQueries().build()
    val runStore = RunStore(db)
    val threads = FakeThreadStore()
    private val pending = PendingRuns()
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
                    screenLease = FakeScreenLease(),
                    deviceState = FakeDeviceStateProvider(steerUnlockedState()),
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
            deviceState = FakeDeviceStateProvider(steerUnlockedState()),
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
            titleGenerator = NoopSteerTitleGenerator,
            ids = UuidV7IdGenerator(clock),
            clock = clock,
            configProvider = config,
            logger = FakeLogger,
            scope = scope,
        )

    val coordinator =
        RunCoordinator(
            executor = executor,
            observer = RunObserver(runStore, config, clock, FakeFlowProgressFeed),
            interaction = ChatUserInteraction(runStore, FakeLogger),
            host = NoopHostStarter,
            control = NoopRunControl,
            recovery =
                InterruptedRunRecovery(
                    runStore = runStore,
                    threadStore = threads,
                    prompts = FakePromptRepository(),
                    executor = executor,
                    clock = clock,
                    logger = FakeLogger,
                ),
            shield = FakeTouchShield(),
            logger = FakeLogger,
            flowStore = FakeFlowStore,
            flowValidation = NoopFlowValidation,
            runStore = runStore,
            threadStore = threads,
        )

    /** 落一条指定状态的 Run。 */
    suspend fun saveRun(
        id: String,
        status: RunStatus,
    ): RunRecord {
        val record =
            RunRecord(
                id = RunId(id),
                taskId = TaskId("t1"),
                threadId = ThreadId("thread-$id"),
                origin = RunOrigin.CHAT,
                status = status,
                triggerContext = emptyMap(),
                createdAt = STEER_START_MS,
                startedAt = if (status == RunStatus.QUEUED) null else STEER_START_MS,
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

/** 标题生成空实现。 */
private object NoopSteerTitleGenerator : ChatTitleGenerator {
    override suspend fun generate(firstMessage: String): String? = null
}

/** 不拉起前台服务。 */
private object NoopHostStarter : AgentHostStarter {
    override fun ensureRunning(): Unit = Unit
}

/** 等待中控制空转。 */
private object NoopRunControl : RunControl {
    override fun requeueNow(runId: RunId): Unit = Unit

    override fun snooze(
        runId: RunId,
        minutes: Int,
    ): Unit = Unit

    override fun cancelWaiting(runId: RunId): Unit = Unit

    override fun resumeBlocked(
        runId: RunId,
        note: String?,
    ): Unit = Unit
}

/** 流程校验空转。 */
private object NoopFlowValidation : FlowValidationService {
    override suspend fun validate(definition: FlowDefinition): List<FlowIssue> = emptyList()

    override suspend fun needsScreen(definition: FlowDefinition): Boolean = false
}

/** 未锁屏的设备状态。 */
private fun steerUnlockedState(): DeviceState =
    DeviceState(
        batteryPercent = 80,
        charging = false,
        networkType = "wifi",
        screenOn = true,
        keyguardLocked = false,
        foregroundPackage = null,
    )
