/**
 * RunRunner 的回放集成（docs/tech/04 §3.12，04 §6 第 19 项，R1/A6）：
 * 成功 Run 收口后录制回放脚本存回 Task；定时/条件触发且有脚本时把 ReplayPlan
 * 塞进 RunRequest 优先回放；CHAT 与 `task.replay.enabled=false` 不走。
 */
package com.example.autop.task.impl

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.agent.ReplayScript
import com.example.autop.agent.ReplayStateSample
import com.example.autop.agent.ReplayableAction
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ToolResultEvent
import com.example.autop.core.common.RunId
import com.example.autop.core.common.TaskId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.device.DeviceState
import com.example.autop.llm.Block
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.Task
import com.example.autop.task.Trigger
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 调度起点（FakeClock 初值）。 */
private const val REPLAY_START_MS: Long = 10_000L

/** 回放集成行为（docs/tech/04 §3.12）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class RunRunnerReplayTest {
    @Test
    public fun `成功 Run 收口后录制回放脚本存回 Task`() =
        runTest {
            val fixture = ReplayFixture(backgroundScope, events = successEvents("run-1"))
            val task = replayTaskOf("t1")

            fixture.run("run-1", RunOrigin.SCHEDULE, task)

            val saved = fixture.tasks.saved.single()
            assertNotNull(saved.replayScript)
            assertEquals(RunId("run-1"), saved.replayScript?.sourceRunId)
            assertEquals(listOf("tool.tap"), saved.replayScript?.steps?.map { it.toolId })
        }

    @Test
    public fun `定时触发且有脚本时优先回放`() =
        runTest {
            val fixture = ReplayFixture(backgroundScope)
            val task = replayTaskOf("t1").copy(replayScript = scriptOf("tool.tap"))

            fixture.run("run-1", RunOrigin.SCHEDULE, task)

            val replay =
                fixture.session.requests
                    .single()
                    .replay
            assertNotNull(replay)
            assertEquals(listOf("tool.tap"), replay?.script?.steps?.map { it.toolId })
            assertEquals(0.85, replay?.config?.threshold)
        }

    @Test
    public fun `条件触发且有脚本时优先回放`() =
        runTest {
            val fixture = ReplayFixture(backgroundScope)
            val task = replayTaskOf("t1").copy(replayScript = scriptOf("tool.tap"))

            fixture.run("run-1", RunOrigin.CONDITION, task)

            assertNotNull(
                fixture.session.requests
                    .single()
                    .replay,
            )
        }

    @Test
    public fun `聊天触发不走回放`() =
        runTest {
            val fixture = ReplayFixture(backgroundScope)
            val task = replayTaskOf("t1").copy(replayScript = scriptOf("tool.tap"))

            fixture.run("run-1", RunOrigin.CHAT, task)

            assertNull(
                fixture.session.requests
                    .single()
                    .replay,
            )
            assertTrue(fixture.tasks.saved.isEmpty())
        }

    @Test
    public fun `task replay 开关关闭时不录制也不回放`() =
        runTest {
            val fixture =
                ReplayFixture(
                    backgroundScope,
                    events = successEvents("run-1"),
                    config = FakeConfigProvider(replayDisabled()),
                )
            val task = replayTaskOf("t1").copy(replayScript = scriptOf("tool.tap"))

            fixture.run("run-1", RunOrigin.SCHEDULE, task)

            assertTrue(fixture.tasks.saved.isEmpty())
            assertNull(
                fixture.session.requests
                    .single()
                    .replay,
            )
        }
}

/** 测试装配：真实 RunStore/RunRunner + fake 端口（docs/tech/07 §9）。 */
private class ReplayFixture(
    scope: CoroutineScope,
    events: List<ThreadEvent> = emptyList(),
    config: FakeConfigProvider = FakeConfigProvider(),
) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(start = REPLAY_START_MS)
    private val db: TasksDb =
        Room.inMemoryDatabaseBuilder(context, TasksDb::class.java).allowMainThreadQueries().build()
    private val runStore = RunStore(db)
    private val pending = PendingRuns()
    val session = FakeAgentSession()
    val tasks = FakeTaskRepository()
    private val runner =
        RunRunner(
            agentSession = session,
            runStore = runStore,
            pending = pending,
            observer = RunObserver(runStore, config, clock),
            touchShieldWatcher = TouchShieldWatcher(FakeTouchShield()),
            screenLease = FakeScreenLease(),
            deviceState = FakeDeviceStateProvider(replayUnlockedState()),
            appLauncher = FakeAppLauncher(),
            notifier = UserNotifierImpl(context, config),
            taskStore = tasks,
            threadStore = FakeThreadStore(items = events),
            experience = FakeExperienceExtractor(),
            configProvider = config,
            clock = clock,
            scope = scope,
            logger = FakeLogger,
        )

    suspend fun run(
        id: String,
        origin: RunOrigin,
        task: Task,
    ) {
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
        runner.execute(record, task)
    }
}

/** 未锁屏的设备状态。 */
private fun replayUnlockedState(): DeviceState =
    DeviceState(
        batteryPercent = 80,
        charging = false,
        networkType = "wifi",
        screenOn = true,
        keyguardLocked = false,
        foregroundPackage = null,
    )

/** 有回放步骤的任务。 */
private fun replayTaskOf(id: String): Task =
    Task(
        id = TaskId(id),
        name = "task-$id",
        goal = "goal",
        trigger = Trigger.Manual,
        createdAt = REPLAY_START_MS,
        updatedAt = REPLAY_START_MS,
    )

/** 一步回放脚本。 */
private fun scriptOf(toolId: String): ReplayScript =
    ReplayScript(
        sourceRunId = RunId("run-0"),
        steps = listOf(actionOf(toolId)),
    )

private fun actionOf(toolId: String): ReplayableAction =
    ReplayableAction(
        toolId = toolId,
        replayArgs = JsonObject(emptyMap()),
        pre =
            ReplayStateSample(
                packageName = "com.example",
                activity = "Main",
                fingerprint = "fp",
                keys = listOf("k"),
            ),
        postFingerprint = null,
        execution = "node_action",
    )

/** 一次成功 Run 的线程事件：一个成功动作 + SUCCEEDED 收口。 */
private fun successEvents(runId: String): List<ThreadEvent> {
    val threadId = ThreadId("thread-$runId")
    val result =
        ToolResultEvent(
            callId = "c1",
            toolId = "tool.tap",
            ok = true,
            blocks = listOf(Block.Text("结果")),
            errorCode = null,
            durationMs = 1,
            replay = actionOf("tool.tap"),
        )
    return listOf(
        ThreadEvent(threadId, 1, RunId(runId), REPLAY_START_MS, result),
        ThreadEvent(
            threadId,
            2,
            RunId(runId),
            REPLAY_START_MS + 1,
            RunFinished(status = RunOutcome.SUCCEEDED, summary = "ok"),
        ),
    )
}

/** `task.replay.enabled=false` 的配置覆盖。 */
private fun replayDisabled(): JsonObject =
    buildJsonObject {
        put(
            "task",
            buildJsonObject {
                put(
                    "replay",
                    buildJsonObject {
                        put("enabled", false)
                    },
                )
            },
        )
    }
