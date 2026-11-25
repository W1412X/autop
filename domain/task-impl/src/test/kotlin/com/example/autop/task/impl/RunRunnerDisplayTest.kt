/** Run 级显示目标生命周期：真实屏兼容、后台屏隔离、打开失败与所有退出路径释放。 */
package com.example.autop.task.impl

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.agent.AgentSession
import com.example.autop.agent.PauseReason
import com.example.autop.agent.ReplayScript
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunHandle
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunRequest
import com.example.autop.agent.RunState
import com.example.autop.agent.ScreenAccess
import com.example.autop.core.common.AppError
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.TaskId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.DeviceState
import com.example.autop.device.DisplayTarget
import com.example.autop.flow.FlowDebugControl
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowProgress
import com.example.autop.flow.FlowRunHandle
import com.example.autop.flow.FlowRunOptions
import com.example.autop.flow.FlowRunRequest
import com.example.autop.flow.FlowSession
import com.example.autop.flow.PauseResolution
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.Task
import com.example.autop.task.Trigger
import com.example.autop.task.impl.db.TasksDb
import com.example.autop.task.impl.notify.UserNotifierImpl
import com.example.autop.task.impl.store.RunStore
import com.example.autop.task.impl.testing.FakeAppLauncher
import com.example.autop.task.impl.testing.FakeDeviceStateProvider
import com.example.autop.task.impl.testing.FakeLogger
import com.example.autop.task.impl.testing.FakeRunDisplayController
import com.example.autop.task.impl.testing.FakeScreenLease
import com.example.autop.task.impl.testing.FakeTaskRepository
import com.example.autop.task.impl.testing.FakeThreadStore
import com.example.autop.task.impl.testing.FakeTouchShield
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val DISPLAY_START_MS: Long = 10_000L

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class RunRunnerDisplayTest {
    @Test
    public fun `feature off keeps main screen gate shield and origin behavior`() =
        runTest {
            var acquired = false
            val session =
                RecordingAgentSession { request ->
                    TestHandle(request.runId) {
                        acquired = request.screenGate?.acquire() == ScreenAccess.HELD
                        succeeded()
                    }
                }
            val fixture = DisplayFixture(backgroundScope, agentSession = session)

            fixture.run()

            assertTrue(acquired)
            assertNotNull(fixture.sessionRequest().screenGate)
            assertSame(DisplayTarget.Main, fixture.agentScope().coroutineContext[DisplayTarget])
            assertEquals(1, fixture.shield.resetCount)
            assertTrue(fixture.devices.currentCalls > 0)
            assertEquals(listOf("com.example.origin"), fixture.launcher.launched)
            assertNull(fixture.screenLease.holder.value)
            assertEquals(listOf(RunId("run-1")), fixture.displays.released)
        }

    @Test
    public fun `background skips real lease shield and origin handling`() =
        runTest {
            val fixture = backgroundFixture(initialHolder = "real-run")

            fixture.run()

            val request = fixture.sessionRequest()
            assertNull(request.screenGate)
            assertTrue(fixture.agentScope().coroutineContext[DisplayTarget] is DisplayTarget.Background)
            assertEquals(0, fixture.screenLease.leaseCalls)
            assertEquals("real-run", fixture.screenLease.holder.value)
            assertEquals(0, fixture.shield.resetCount)
            assertFalse(fixture.shield.lastActive)
            assertEquals(0, fixture.devices.currentCalls)
            assertTrue(fixture.launcher.launched.isEmpty())
            assertEquals(listOf(RunId("run-1")), fixture.displays.released)
        }

    @Test
    public fun `open error code is preserved without agent or real screen fallback`() =
        runTest {
            val code = DeviceErrorCodes.BG_CHANNEL_UNAVAILABLE
            val displays = FakeRunDisplayController { Outcome.Err(AppError(code, "host unavailable")) }
            val fixture = backgroundFixture(displays = displays)

            fixture.run()

            assertTrue(fixture.agent.requests.isEmpty())
            val record = fixture.record()
            assertEquals(RunStatus.FAILED, record.status)
            assertEquals(code, record.errorCode)
            val finished =
                fixture.threads.appended
                    .single()
                    .payload as RunFinished
            assertEquals(code, finished.errorCode)
            assertEquals(0, fixture.screenLease.leaseCalls)
            assertEquals(0, fixture.shield.resetCount)
            assertEquals(listOf(RunId("run-1")), displays.released)
        }

    @Test
    public fun `background request rejects a physical target returned by the controller`() =
        runTest {
            val fixture = backgroundFixture(displays = FakeRunDisplayController())

            fixture.run()

            assertEquals(DeviceErrorCodes.BG_TARGET_CHANGED, fixture.record().errorCode)
            assertTrue(fixture.agent.requests.isEmpty())
            assertEquals(0, fixture.shield.resetCount)
        }

    @Test
    public fun `unsupported sdk open error is preserved`() =
        runTest {
            val code = DeviceErrorCodes.BG_UNSUPPORTED_SDK
            val displays = FakeRunDisplayController { Outcome.Err(AppError(code, "unsupported")) }
            val fixture = backgroundFixture(displays = displays)

            fixture.run()

            assertEquals(code, fixture.record().errorCode)
            assertTrue(fixture.agent.requests.isEmpty())
            assertEquals(listOf(RunId("run-1")), displays.released)
        }

    @Test
    public fun `display is released when agent fails`() =
        runTest {
            val session =
                RecordingAgentSession { request ->
                    TestHandle(request.runId) { throw IllegalStateException("boom") }
                }
            val fixture = backgroundFixture(agentSession = session)

            fixture.run()

            assertEquals(RunStatus.FAILED, fixture.record().status)
            assertEquals(CommonErrorCodes.UNKNOWN, fixture.record().errorCode)
            assertEquals(listOf(RunId("run-1")), fixture.displays.released)
        }

    @Test
    public fun `display is released when run is cancelled`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val session =
                RecordingAgentSession { request ->
                    started.complete(Unit)
                    TestHandle(request.runId) { awaitCancellation() }
                }
            val fixture = backgroundFixture(agentSession = session)
            val job = launch { fixture.run() }

            started.await()
            assertTrue(fixture.agent.requests.isNotEmpty())

            job.cancelAndJoin()

            assertEquals(RunStatus.CANCELLED, fixture.record().status)
            assertEquals(listOf(RunId("run-1")), fixture.displays.released)
        }

    @Test
    public fun `display is released when cancelled while opening`() =
        runTest {
            val opening = CompletableDeferred<Unit>()
            val displays =
                FakeRunDisplayController {
                    opening.complete(Unit)
                    awaitCancellation()
                }
            val fixture = backgroundFixture(displays = displays)
            val job = launch { fixture.run() }

            opening.await()
            RunDisplayTargetRegistry.forceRelease(RunId("run-1"), fixture.shield)
            assertFalse(fixture.shield.forceReleased)
            job.cancelAndJoin()

            assertEquals(RunStatus.CANCELLED, fixture.record().status)
            assertTrue(fixture.agent.requests.isEmpty())
            assertEquals(listOf(RunId("run-1")), displays.released)
        }

    @Test
    public fun `display is released for replay runs`() =
        runTest {
            val fixture = backgroundFixture()
            val task = taskOf().copy(replayScript = ReplayScript(sourceRunId = RunId("source"), steps = emptyList()))

            fixture.run(task = task)

            assertNotNull(fixture.sessionRequest().replay)
            assertEquals(listOf(RunId("run-1")), fixture.displays.released)
        }

    @Test
    public fun `flow and nested agent inherit the same background display context`() =
        runTest {
            lateinit var target: DisplayTarget
            val displays =
                FakeRunDisplayController { owner ->
                    target = DisplayTarget.Background(owner, displayId = 5, width = 1080, height = 1920, dpi = 320)
                    Outcome.Ok(target)
                }
            val childAgent = RecordingAgentSession()
            val flowSession =
                RecordingFlowSession { request, scope ->
                    assertSame(target, scope.coroutineContext[DisplayTarget])
                    childAgent.start(
                        RunRequest(
                            runId = RunId("child-run"),
                            threadId = ThreadId("child-thread"),
                            input = emptyList(),
                            goal = "child",
                            vars = emptyMap(),
                            config = request.config,
                            toolFilter = null,
                        ),
                        scope,
                    )
                    FakeFlowRunHandle(request.runId)
                }
            val fixture = backgroundFixture(displays = displays, flowSession = flowSession)
            val flow =
                FlowLaunch(
                    definition = FlowDefinition(id = "flow-1", name = "flow"),
                    params = JsonObject(emptyMap()),
                    options = FlowRunOptions(),
                    needsScreen = true,
                )

            fixture.run(task = null, flow = flow)

            assertSame(target, childAgent.scopes.single().coroutineContext[DisplayTarget])
            assertEquals(0, fixture.screenLease.leaseCalls)
            assertEquals(0, fixture.shield.resetCount)
            assertEquals(listOf(RunId("run-1")), displays.released)
        }

    @Test
    public fun `conflicting inherited display is rejected explicitly`() =
        runTest {
            val inherited =
                DisplayTarget.Background(RunId("other-run"), displayId = 8, width = 1080, height = 1920, dpi = 320)
            val fixture =
                backgroundFixture(scope = CoroutineScope(backgroundScope.coroutineContext + inherited))

            fixture.run()

            assertTrue(fixture.agent.requests.isEmpty())
            assertEquals(RunStatus.FAILED, fixture.record().status)
            assertEquals(DeviceErrorCodes.BG_RELEASED, fixture.record().errorCode)
            assertEquals(0, fixture.screenLease.leaseCalls)
            assertEquals(listOf(RunId("run-1")), fixture.displays.released)
        }

    private fun TestScope.backgroundFixture(
        displays: FakeRunDisplayController = FakeRunDisplayController { owner -> backgroundTarget(owner) },
        agentSession: RecordingAgentSession = RecordingAgentSession(),
        flowSession: FlowSession = FakeFlowSession,
        initialHolder: String? = null,
        scope: CoroutineScope = backgroundScope,
    ): DisplayFixture =
        DisplayFixture(
            scope = scope,
            config = FakeConfigProvider(flowOverride()),
            displays = displays,
            agentSession = agentSession,
            flowSession = flowSession,
            initialHolder = initialHolder,
            backgroundScreen = true,
        )
}

private class DisplayFixture(
    scope: CoroutineScope,
    val config: FakeConfigProvider = FakeConfigProvider(),
    val displays: FakeRunDisplayController = FakeRunDisplayController(),
    val agentSession: RecordingAgentSession = RecordingAgentSession(),
    flowSession: FlowSession = FakeFlowSession,
    initialHolder: String? = null,
    private val backgroundScreen: Boolean = false,
) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(start = DISPLAY_START_MS)
    private val db: TasksDb =
        Room.inMemoryDatabaseBuilder(context, TasksDb::class.java).allowMainThreadQueries().build()
    val runStore = RunStore(db)
    private val pending = PendingRuns()
    val threads = FakeThreadStore()
    val shield = FakeTouchShield()
    val screenLease = FakeScreenLease(initialHolder)
    val devices = FakeDeviceStateProvider(unlockedState())
    val launcher = FakeAppLauncher()
    val agent = agentSession
    private val runner =
        RunRunner(
            agentSession = agentSession,
            flowSession = flowSession,
            flowStore = FakeFlowStore,
            flowFeed = FakeFlowProgressFeed,
            runStore = runStore,
            pending = pending,
            observer = RunObserver(runStore, config, clock, FakeFlowProgressFeed),
            touchShieldWatcher = TouchShieldWatcher(shield, displays),
            screenLease = screenLease,
            deviceState = devices,
            appLauncher = launcher,
            notifier = UserNotifierImpl(context, config),
            taskStore = FakeTaskRepository(),
            threadStore = threads,
            configProvider = config,
            prompts = FakePromptRepository(),
            clock = clock,
            scope = scope,
            logger = FakeLogger,
        )

    suspend fun run(
        id: String = "run-1",
        task: Task? = taskOf(),
        flow: FlowLaunch? = null,
    ) {
        val record =
            RunRecord(
                id = RunId(id),
                taskId = task?.id,
                threadId = ThreadId("thread-$id"),
                origin = if (task?.replayScript != null) RunOrigin.SCHEDULE else RunOrigin.CHAT,
                status = RunStatus.QUEUED,
                triggerContext = emptyMap(),
                createdAt = clock.nowMillis(),
                startedAt = null,
                endedAt = null,
                summary = null,
                errorCode = null,
                pauseReason = null,
                waitingQuestion = null,
                backgroundScreen = backgroundScreen,
            )
        runStore.save(record)
        pending.put(
            record.id,
            PendingEntry(vars = emptyMap(), task = task, flow = flow),
        )
        runner.execute(record, task)
    }

    fun sessionRequest(): RunRequest = agent.requests.single()

    fun agentScope(): CoroutineScope = agent.scopes.single()

    suspend fun record(): RunRecord = runStore.get(RunId("run-1")) ?: error("run not found")
}

private class RecordingAgentSession(
    private val handleFactory: (RunRequest) -> RunHandle = { TestHandle(it.runId) { succeeded() } },
) : AgentSession {
    val requests: MutableList<RunRequest> = mutableListOf()
    val scopes: MutableList<CoroutineScope> = mutableListOf()

    override fun start(
        request: RunRequest,
        scope: CoroutineScope,
    ): RunHandle {
        requests += request
        scopes += scope
        return handleFactory(request)
    }

    override suspend fun fork(
        threadId: ThreadId,
        atSeq: Long,
        title: String?,
    ): Outcome<ThreadId> = error("not used")
}

private class TestHandle(
    override val runId: RunId,
    private val result: suspend () -> RunFinished,
) : RunHandle {
    override val events: SharedFlow<com.example.autop.agent.AgentEvent> = MutableSharedFlow()
    override val state: StateFlow<RunState> = MutableStateFlow(RunState.FINISHED)

    override fun steer(text: String): Unit = Unit

    override fun pause(
        reason: PauseReason,
        detail: String?,
    ): Unit = Unit

    override fun resume(note: String?): Unit = Unit

    override fun cancel(): Unit = Unit

    override suspend fun await(): RunFinished = result()
}

private class RecordingFlowSession(
    private val onStart: (FlowRunRequest, CoroutineScope) -> FlowRunHandle,
) : FlowSession {
    override fun start(
        request: FlowRunRequest,
        scope: CoroutineScope,
    ): FlowRunHandle = onStart(request, scope)

    override suspend fun applyPatch(
        flowId: String,
        definition: FlowDefinition,
    ): Outcome<Unit> = Outcome.Ok(Unit)
}

private class FakeFlowRunHandle(
    override val runId: RunId,
) : FlowRunHandle {
    override val events: SharedFlow<com.example.autop.agent.AgentEvent> = MutableSharedFlow()
    override val state: StateFlow<RunState> = MutableStateFlow(RunState.FINISHED)
    override val progress: StateFlow<FlowProgress> = MutableStateFlow(FlowProgress())

    override fun steer(text: String): Unit = Unit

    override fun pause(
        reason: PauseReason,
        detail: String?,
    ): Unit = Unit

    override fun resume(note: String?): Unit = Unit

    override fun cancel(): Unit = Unit

    override suspend fun await(): RunFinished = succeeded()

    override fun debug(): FlowDebugControl =
        object : FlowDebugControl {
            override fun step(): Unit = Unit

            override fun resumeRun(): Unit = Unit

            override fun setBreakpoints(nodeIds: Set<String>): Unit = Unit

            override fun resolvePause(resolution: PauseResolution): Unit = Unit
        }

    override suspend fun applyPatch(definition: FlowDefinition): Outcome<Unit> = Outcome.Ok(Unit)
}

private fun succeeded(): RunFinished = RunFinished(status = RunOutcome.SUCCEEDED, summary = "ok")

private fun backgroundTarget(owner: RunId): Outcome<DisplayTarget> =
    Outcome.Ok(DisplayTarget.Background(owner, displayId = 7, width = 1080, height = 1920, dpi = 320))

private fun flowOverride(): JsonObject =
    buildJsonObject {
        put(
            "features",
            buildJsonObject {
                put("flows", true)
            },
        )
    }

private fun taskOf(): Task =
    Task(
        id = TaskId("task-1"),
        name = "task",
        goal = "goal",
        trigger = Trigger.Manual,
        createdAt = DISPLAY_START_MS,
        updatedAt = DISPLAY_START_MS,
    )

private fun unlockedState(): DeviceState =
    DeviceState(
        batteryPercent = 80,
        charging = false,
        networkType = "wifi",
        screenOn = true,
        keyguardLocked = false,
        foregroundPackage = "com.example.origin",
    )
