/**
 * [RunRunner] 的经验提炼接线（docs/design/agent-design.md §8）：成功 Run 收口后把
 * 最后涉及的应用包名与步骤摘要交给 [ExperienceExtractor]；失败 Run 与无屏幕观测
 * 的 Run 不触发。
 */
package com.example.autop.task.impl

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.agent.ObservationRef
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ToolResultEvent
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.device.DeviceState
import com.example.autop.llm.Block
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.impl.db.TasksDb
import com.example.autop.task.impl.notify.UserNotifierImpl
import com.example.autop.task.impl.store.RunStore
import com.example.autop.task.impl.testing.FakeAgentSession
import com.example.autop.task.impl.testing.FakeAppLauncher
import com.example.autop.task.impl.testing.FakeDeviceStateProvider
import com.example.autop.task.impl.testing.FakeExperienceExtractor
import com.example.autop.task.impl.testing.FakeLogger
import com.example.autop.task.impl.testing.FakeScreenLease
import com.example.autop.task.impl.testing.FakeTaskRepository
import com.example.autop.task.impl.testing.FakeThreadStore
import com.example.autop.task.impl.testing.FakeTouchShield
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 时钟起点（FakeClock 初值）。 */
private const val EXPERIENCE_START_MS: Long = 10_000L

/** 经验提炼触发行为（docs/design/agent-design.md §8）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class RunRunnerExperienceTest {
    @Test
    public fun `成功 Run 收口后触发经验提炼`() =
        runTest {
            val fixture =
                ExperienceFixture(
                    backgroundScope,
                    experienceEvents("run-1", ok = true, packageName = "com.example.app"),
                )

            fixture.run("run-1")

            val request = fixture.experience.requests.single()
            assertEquals(RunId("run-1"), request.runId)
            assertEquals("com.example.app", request.packageName)
            assertTrue(request.steps.contains("tool.tap"))
        }

    @Test
    public fun `失败 Run 不触发提炼`() =
        runTest {
            val fixture =
                ExperienceFixture(
                    backgroundScope,
                    experienceEvents("run-1", ok = false, packageName = "com.example.app"),
                    sessionFinished = RunFinished(status = RunOutcome.FAILED, summary = null),
                )

            fixture.run("run-1")

            assertTrue(fixture.experience.requests.isEmpty())
        }

    @Test
    public fun `无屏幕观测时不触发提炼`() =
        runTest {
            val fixture =
                ExperienceFixture(backgroundScope, experienceEvents("run-1", ok = true, packageName = null))

            fixture.run("run-1")

            assertTrue(fixture.experience.requests.isEmpty())
        }
}

/** 测试装配：真实 RunStore/RunRunner + fake 端口（docs/tech/07 §9）。 */
private class ExperienceFixture(
    scope: CoroutineScope,
    events: List<ThreadEvent>,
    sessionFinished: RunFinished = RunFinished(status = RunOutcome.SUCCEEDED, summary = "ok"),
) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(start = EXPERIENCE_START_MS)
    private val config = FakeConfigProvider()
    private val db: TasksDb =
        Room.inMemoryDatabaseBuilder(context, TasksDb::class.java).allowMainThreadQueries().build()
    private val runStore = RunStore(db)
    private val pending = PendingRuns()
    val experience = FakeExperienceExtractor()
    private val runner =
        RunRunner(
            agentSession = FakeAgentSession(finished = sessionFinished),
            flowSession = FakeFlowSession,
            flowStore = FakeFlowStore,
            runStore = runStore,
            pending = pending,
            observer = RunObserver(runStore, config, clock),
            touchShieldWatcher = TouchShieldWatcher(FakeTouchShield()),
            screenLease = FakeScreenLease(),
            deviceState = FakeDeviceStateProvider(experienceUnlockedState()),
            appLauncher = FakeAppLauncher(),
            notifier = UserNotifierImpl(context, config),
            taskStore = FakeTaskRepository(),
            threadStore = FakeThreadStore(items = events),
            experience = experience,
            configProvider = config,
            clock = clock,
            scope = scope,
            logger = FakeLogger,
        )

    /** 执行一个无任务的 Run（经验提炼与 Task 无关）。 */
    suspend fun run(id: String) {
        val record =
            RunRecord(
                id = RunId(id),
                taskId = null,
                threadId = ThreadId("thread-$id"),
                origin = RunOrigin.CHAT,
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
        pending.put(record.id, PendingEntry(input = emptyList(), vars = emptyMap(), task = null))
        runner.execute(record, null)
    }
}

/** 一次 Run 的线程事件：一个工具结果（可带观测）+ RunFinished 收口。 */
private fun experienceEvents(
    runId: String,
    ok: Boolean,
    packageName: String?,
): List<ThreadEvent> {
    val threadId = ThreadId("thread-$runId")
    val result =
        ToolResultEvent(
            callId = "c1",
            toolId = "tool.tap",
            ok = ok,
            blocks = listOf(Block.Text("已点击目标")),
            errorCode = if (ok) null else "tool.failed",
            observation =
                packageName?.let {
                    ObservationRef(
                        fingerprint = "fp",
                        packageName = it,
                        activity = "Main",
                        summaryLine = "首页",
                        nodeCount = 1,
                        hasImage = false,
                    )
                },
            durationMs = 1,
        )
    return listOf(
        ThreadEvent(threadId, 1, RunId(runId), EXPERIENCE_START_MS, result),
        ThreadEvent(
            threadId,
            2,
            RunId(runId),
            EXPERIENCE_START_MS + 1,
            RunFinished(status = if (ok) RunOutcome.SUCCEEDED else RunOutcome.FAILED, summary = null),
        ),
    )
}

/** 未锁屏的设备状态。 */
private fun experienceUnlockedState(): DeviceState =
    DeviceState(
        batteryPercent = 80,
        charging = false,
        networkType = "wifi",
        screenOn = true,
        keyguardLocked = false,
        foregroundPackage = null,
    )
