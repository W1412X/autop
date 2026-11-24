/**
 * [ChatThreadViewModel] 单测（agent-harness-overhaul WP8.3/WP8.5）：Stop 只作用于
 * 本线程的 openRun、steer 失败保留输入框并提示、fork 切线程停掉旧订阅。
 */
package com.example.autop.feature.chat.thread

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.AgentSession
import com.example.autop.agent.EventPayload
import com.example.autop.agent.RunState
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ThreadSummary
import com.example.autop.core.common.AppError
import com.example.autop.core.common.BackgroundRunBlock
import com.example.autop.core.common.Capability
import com.example.autop.core.common.ImageAttachment
import com.example.autop.core.common.LogFiles
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.PlatformCapabilities
import com.example.autop.core.common.RunId
import com.example.autop.core.common.RunMode
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.core.thread.ui.ChatImageReader
import com.example.autop.core.thread.ui.TimelineItem
import com.example.autop.device.AccessibilityStatus
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunService
import com.example.autop.task.RunStatus
import com.example.autop.task.Task
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 对话页行为（WP8.3/WP8.5）。 */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher（协程测试调度）
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class ChatThreadViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    public fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    public fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    public fun `stop targets open run of this thread only`() =
        runTest(testDispatcher) {
            val threads = FakeChatThreadStore()
            val runService = FakeChatRunService()
            // 悬浮球语义的全局活跃 Run 在另一线程；本线程有自己的 openRun。
            runService.active.value = activeInfo(record("r2", ThreadId("t2")))
            runService.openRuns["t1"] = MutableStateFlow(record("r1", ThreadId("t1")))
            val viewModel = createViewModel(threads, runService)

            viewModel.bindThread("t1")
            viewModel.onAction(ChatThreadAction.Stop)

            assertThat(runService.cancelled.map { it.value }).containsExactly("r1")
        }

    @Test
    public fun `failed steer keeps input`() =
        runTest(testDispatcher) {
            val threads = FakeChatThreadStore()
            val runService = FakeChatRunService()
            runService.openRuns["t1"] = MutableStateFlow(record("r1", ThreadId("t1")))
            runService.steerResult = Outcome.Err(AppError(code = "task.invalid", message = "run is not active"))
            val viewModel = createViewModel(threads, runService)
            viewModel.bindThread("t1")

            viewModel.onAction(ChatThreadAction.SetInput("换个说法再发一次"))
            viewModel.onAction(ChatThreadAction.Send)

            assertThat(viewModel.uiState.value.input).isEqualTo("换个说法再发一次")
            assertThat(viewModel.uiState.value.notice).isEqualTo(ThreadNotice.STEER_FAILED)
        }

    @Test
    public fun `fork cancels previous collection`() =
        runTest(testDispatcher) {
            val threads = FakeChatThreadStore()
            val runService = FakeChatRunService()
            val session = FakeForkSession()
            val viewModel = createViewModel(threads, runService, session)
            viewModel.bindThread("t1")
            threads.append(ThreadId("t1"), null, userInput("旧线程消息"))
            assertThat(hasUserText(viewModel, "旧线程消息")).isTrue()

            viewModel.onAction(ChatThreadAction.ForkFrom(1))

            // 切到分叉线程后，旧订阅已停：旧线程的新事件不再追加，新线程事件正常。
            threads.append(ThreadId("t1"), null, userInput("旧线程新消息"))
            threads.append(ThreadId("t2"), null, userInput("新线程消息"))
            assertThat(hasUserText(viewModel, "旧线程消息")).isFalse()
            assertThat(hasUserText(viewModel, "旧线程新消息")).isFalse()
            assertThat(hasUserText(viewModel, "新线程消息")).isTrue()
        }

    @Test
    public fun `run mode stays after a message is sent in the same thread`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeChatThreadStore(), FakeChatRunService())
            viewModel.bindThread("t1")

            viewModel.onAction(ChatThreadAction.SetRunMode(RunMode.CHAT_ONLY))
            viewModel.onAction(ChatThreadAction.SetInput("你好"))
            viewModel.onAction(ChatThreadAction.Send)

            assertThat(viewModel.uiState.value.runMode).isEqualTo(RunMode.CHAT_ONLY)
        }

    @Test
    public fun `run mode is restored from the thread when it is reopened`() =
        runTest(testDispatcher) {
            val threads = FakeChatThreadStore()
            val runService = FakeChatRunService()
            val first = createViewModel(threads, runService)
            first.bindThread("t1")
            first.onAction(ChatThreadAction.SetRunMode(RunMode.CHAT_ONLY))

            val reopened = createViewModel(threads, runService)
            reopened.bindThread("t1")

            assertThat(reopened.uiState.value.runMode).isEqualTo(RunMode.CHAT_ONLY)
            assertThat(threads.runModeOf(ThreadId("t1"))).isEqualTo(RunMode.CHAT_ONLY)
        }

    @Test
    public fun `fork persists the current run mode on the new thread`() =
        runTest(testDispatcher) {
            val threads = FakeChatThreadStore()
            val viewModel = createViewModel(threads, FakeChatRunService(), FakeForkSession())
            viewModel.bindThread("t1")
            viewModel.onAction(ChatThreadAction.SetRunMode(RunMode.CHAT_ONLY))

            viewModel.onAction(ChatThreadAction.ForkFrom(1))

            assertThat(threads.runModeOf(ThreadId("t2"))).isEqualTo(RunMode.CHAT_ONLY)
        }

    @Test
    public fun `background mode persists and is captured for each send`() =
        runTest(testDispatcher) {
            val threads = FakeChatThreadStore()
            val runService = FakeChatRunService()
            val viewModel = createViewModel(threads, runService, shellConnected = true)
            viewModel.bindThread("t1")

            viewModel.onAction(ChatThreadAction.SetBackgroundScreen(true))
            viewModel.onAction(ChatThreadAction.SetInput("后台执行"))
            viewModel.onAction(ChatThreadAction.Send)

            assertThat(threads.backgroundScreenOf(ThreadId("t1"))).isTrue()
            assertThat(runService.chatBackgroundModes).containsExactly(true)

            val reopened = createViewModel(threads, runService, shellConnected = true)
            reopened.bindThread("t1")
            assertThat(reopened.uiState.value.backgroundScreen).isTrue()

            reopened.onAction(ChatThreadAction.SetBackgroundScreen(false))
            reopened.onAction(ChatThreadAction.SetInput("真实屏执行"))
            reopened.onAction(ChatThreadAction.Send)
            assertThat(runService.chatBackgroundModes).containsExactly(true, false).inOrder()
        }

    @Test
    public fun `background mode stays locked until the sent run is visible`() =
        runTest(testDispatcher) {
            val threads = FakeChatThreadStore()
            val runService = FakeChatRunService()
            val startGate = CompletableDeferred<Unit>()
            runService.startGate = startGate
            val viewModel = createViewModel(threads, runService, shellConnected = true)
            viewModel.bindThread("t1")
            viewModel.onAction(ChatThreadAction.SetBackgroundScreen(true))
            viewModel.onAction(ChatThreadAction.SetInput("后台执行"))
            viewModel.onAction(ChatThreadAction.Send)
            runCurrent()

            viewModel.onAction(ChatThreadAction.SetBackgroundScreen(false))
            assertThat(viewModel.uiState.value.backgroundScreen).isTrue()
            assertThat(viewModel.uiState.value.backgroundRunBlock).isEqualTo(BackgroundRunBlock.RUNNING)

            startGate.complete(Unit)
            runCurrent()
            assertThat(runService.chatBackgroundModes).containsExactly(true)
            assertThat(viewModel.uiState.value.backgroundRunBlock).isEqualTo(BackgroundRunBlock.RUNNING)

            runService.openRuns.getOrPut("t1") { MutableStateFlow(null) }.value = record("r-new", ThreadId("t1"))
            runCurrent()
            runService.openRuns.getValue("t1").value = null
            runCurrent()
            assertThat(viewModel.uiState.value.backgroundRunBlock).isNull()
            assertThat(threads.backgroundScreenOf(ThreadId("t1"))).isTrue()
        }

    @Test
    public fun `background mode cannot change while this thread is running`() =
        runTest(testDispatcher) {
            val threads = FakeChatThreadStore()
            val runService = FakeChatRunService()
            val viewModel = createViewModel(threads, runService, shellConnected = true)
            viewModel.bindThread("t1")
            viewModel.onAction(ChatThreadAction.SetBackgroundScreen(true))
            runService.openRuns.getOrPut("t1") { MutableStateFlow(null) }.value = record("r1", ThreadId("t1"))
            runCurrent()

            viewModel.onAction(ChatThreadAction.SetBackgroundScreen(false))

            assertThat(viewModel.uiState.value.backgroundScreen).isTrue()
            assertThat(viewModel.uiState.value.backgroundRunBlock).isEqualTo(BackgroundRunBlock.RUNNING)
            assertThat(threads.backgroundScreenOf(ThreadId("t1"))).isTrue()
        }

    @Test
    public fun `background mode reports sdk and channel blockers`() =
        runTest(testDispatcher) {
            val disconnected = createViewModel(FakeChatThreadStore(), FakeChatRunService())
            disconnected.bindThread("t1")
            runCurrent()
            disconnected.onAction(ChatThreadAction.SetBackgroundScreen(true))
            runCurrent()
            assertThat(disconnected.uiState.value.backgroundRunBlock)
                .isEqualTo(BackgroundRunBlock.CHANNEL_DISCONNECTED)
            assertThat(disconnected.uiState.value.backgroundScreen).isFalse()

            val unsupported =
                createViewModel(
                    FakeChatThreadStore(),
                    FakeChatRunService(),
                    shellConnected = true,
                    backgroundSupported = false,
                )
            unsupported.bindThread("t1")
            runCurrent()
            unsupported.onAction(ChatThreadAction.SetBackgroundScreen(true))
            runCurrent()
            assertThat(unsupported.uiState.value.backgroundRunBlock).isEqualTo(BackgroundRunBlock.UNSUPPORTED)
        }

    @Test
    public fun `background mode can be turned off after channel disconnects`() =
        runTest(testDispatcher) {
            val threads = FakeChatThreadStore()
            val shellInput = FakeChatShellInput(connected = true)
            val viewModel =
                createViewModel(
                    threads,
                    FakeChatRunService(),
                    shellInput = shellInput,
                )
            viewModel.bindThread("t1")
            runCurrent()
            viewModel.onAction(ChatThreadAction.SetBackgroundScreen(true))
            runCurrent()
            shellInput.state.value = ShellState.Disconnected
            runCurrent()

            assertThat(viewModel.uiState.value.backgroundRunBlock).isNull()
            viewModel.onAction(ChatThreadAction.SetBackgroundScreen(false))
            assertThat(viewModel.uiState.value.backgroundScreen).isFalse()
            assertThat(viewModel.uiState.value.backgroundRunBlock).isEqualTo(BackgroundRunBlock.CHANNEL_DISCONNECTED)
            assertThat(threads.backgroundScreenOf(ThreadId("t1"))).isFalse()
        }

    @Test
    public fun `fork inherits background mode`() =
        runTest(testDispatcher) {
            val threads = FakeChatThreadStore()
            val viewModel = createViewModel(threads, FakeChatRunService(), FakeForkSession(), shellConnected = true)
            viewModel.bindThread("t1")
            viewModel.onAction(ChatThreadAction.SetBackgroundScreen(true))

            viewModel.onAction(ChatThreadAction.ForkFrom(1))

            assertThat(threads.backgroundScreenOf(ThreadId("t2"))).isTrue()
        }

    private fun hasUserText(
        viewModel: ChatThreadViewModel,
        text: String,
    ): Boolean =
        viewModel.uiState.value.entries
            .any { (it.item as? TimelineItem.UserBubble)?.text == text }

    private fun createViewModel(
        threads: ThreadStore,
        runService: RunService,
        session: AgentSession = FakeForkSession(),
        shellConnected: Boolean = false,
        backgroundSupported: Boolean = true,
        shellInput: ShellInput = FakeChatShellInput(shellConnected),
    ): ChatThreadViewModel =
        ChatThreadViewModel(
            threadStore = threads,
            modelProfiles = FakeChatModelProfiles,
            runService = runService,
            agentSession = session,
            configProvider = FakeConfigProvider(),
            accessibility = FakeChatAccessibility(),
            logFiles = FakeChatLogFiles,
            imageReader =
                ChatImageReader(
                    org.robolectric.RuntimeEnvironment.getApplication(),
                    TestDispatcherProvider(testDispatcher),
                ),
            speech = FakeSpeechInput(),
            shellInput = shellInput,
            platformCapabilities = FakeChatCapabilities(backgroundSupported),
            dispatchers = TestDispatcherProvider(testDispatcher),
        )
}

/** 只读 shell 通道状态（默认未连接，发送 FULL 模式时走阻断分支）。 */
private class FakeChatShellInput(
    connected: Boolean = false,
) : ShellInput {
    override val state: MutableStateFlow<ShellState> =
        MutableStateFlow(if (connected) ShellState.Connected else ShellState.Disconnected)
    override val paired: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun pair(
        pairingPort: Int,
        pairCode: String,
    ): Outcome<Unit> = error("unused")

    override suspend fun connect(): Boolean = false

    override suspend fun probe(): Boolean = false

    override suspend fun tap(
        x: Int,
        y: Int,
        durationMs: Long,
    ): Boolean = error("unused")

    override suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ): Boolean = error("unused")

    override suspend fun keyEvent(code: Int): Boolean = error("unused")

    override suspend fun keyCombination(vararg codes: Int): Boolean = error("unused")
}

private class FakeChatCapabilities(
    private val backgroundSupported: Boolean,
) : PlatformCapabilities {
    override fun has(c: Capability): Boolean = c != Capability.BACKGROUND_VIRTUAL_DISPLAY || backgroundSupported
}

/** 一条用户输入消息载荷。 */
private fun userInput(text: String): EventPayload =
    com.example.autop.agent.UserMessage(
        blocks =
            listOf(
                com.example.autop.llm.Block
                    .Text(text),
            ),
        kind = com.example.autop.agent.UserMessageKind.INPUT,
    )

/** 一个未收口的 Run 记录。 */
private fun record(
    id: String,
    threadId: ThreadId,
): RunRecord =
    RunRecord(
        id = RunId(id),
        taskId = null,
        threadId = threadId,
        origin = RunOrigin.CHAT,
        status = RunStatus.RUNNING,
        triggerContext = emptyMap(),
        createdAt = 1_000L,
        startedAt = 1_000L,
        endedAt = null,
        summary = null,
        errorCode = null,
        pauseReason = null,
        waitingQuestion = null,
    )

/** 活跃 Run 展示信息（另一线程的全局活跃 Run 用）。 */
private fun activeInfo(run: RunRecord): ActiveRunInfo = ActiveRunInfo(run, RunState.SAMPLING, null, 0, emptyList())

/** 按线程过滤、先回放后实时的线程存储假件（WP8.3 语义）。 */
private class FakeChatThreadStore : ThreadStore {
    private val events = linkedMapOf<String, MutableList<ThreadEvent>>()
    private val push = MutableSharedFlow<ThreadEvent>(extraBufferCapacity = 32)
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private var nextThread = 1

    override suspend fun createThread(
        title: String?,
        parent: Pair<ThreadId, Long>?,
    ): ThreadId = ThreadId("thread-${nextThread++}")

    override suspend fun append(
        threadId: ThreadId,
        runId: RunId?,
        payload: EventPayload,
    ): ThreadEvent {
        val threadEvents = events.getOrPut(threadId.value) { mutableListOf() }
        val event = ThreadEvent(threadId, threadEvents.size + 1L, runId, 0L, payload)
        threadEvents += event
        push.emit(event)
        changes.emit(Unit)
        return event
    }

    override suspend fun events(
        threadId: ThreadId,
        fromSeq: Long,
        toSeq: Long,
    ): List<ThreadEvent> = (events[threadId.value].orEmpty()).filter { it.seq in fromSeq..toSeq }

    override suspend fun eventsSinceLatestBaseline(threadId: ThreadId): List<ThreadEvent> =
        events(threadId, 0, Long.MAX_VALUE)

    override fun observeFrom(
        threadId: ThreadId,
        afterSeq: Long,
    ): Flow<List<ThreadEvent>> =
        flow {
            emit(events(threadId, afterSeq + 1, Long.MAX_VALUE))
            emitAll(push.filter { it.threadId == threadId }.map { listOf(it) })
        }

    override fun observe(threadId: ThreadId): Flow<ThreadEvent> = push

    override fun observeChanges(): Flow<Unit> = changes

    override suspend fun saveBlob(
        threadId: ThreadId,
        name: String,
        bytes: ByteArray,
    ): String = "blob/$name"

    override suspend fun listThreads(
        offset: Int,
        limit: Int,
    ): List<ThreadSummary> = emptyList()

    override suspend fun rename(
        threadId: ThreadId,
        title: String,
    ): Unit = Unit

    override suspend fun modelProfileOf(threadId: ThreadId): String? = null

    override suspend fun setModelProfile(
        threadId: ThreadId,
        profileId: String?,
    ) = Unit

    private val runModes = mutableMapOf<String, RunMode>()

    override suspend fun runModeOf(threadId: ThreadId): RunMode = runModes[threadId.value] ?: RunMode.STANDARD

    override suspend fun setRunMode(
        threadId: ThreadId,
        mode: RunMode,
    ) {
        runModes[threadId.value] = mode
    }

    private val backgroundScreens = mutableMapOf<String, Boolean>()

    override suspend fun backgroundScreenOf(threadId: ThreadId): Boolean = backgroundScreens[threadId.value] ?: false

    override suspend fun setBackgroundScreen(
        threadId: ThreadId,
        enabled: Boolean,
    ) {
        backgroundScreens[threadId.value] = enabled
    }

    override suspend fun titleOf(threadId: ThreadId): String? = null

    override suspend fun delete(threadId: ThreadId): Unit = Unit

    override suspend fun forkablePoints(threadId: ThreadId): List<Long> = emptyList()
}

/** [RunService] 假件：openRun 按线程给，steer 结果可注入，记录 cancel。 */
private class FakeChatRunService : RunService {
    val openRuns = mutableMapOf<String, MutableStateFlow<RunRecord?>>()
    val active = MutableStateFlow<ActiveRunInfo?>(null)
    val cancelled = mutableListOf<RunId>()
    val chatBackgroundModes = mutableListOf<Boolean>()

    /** steer 返回结果（默认成功）。 */
    var steerResult: Outcome<Unit> = Outcome.Ok(Unit)

    var startGate: CompletableDeferred<Unit>? = null

    override fun observeOpenRun(threadId: ThreadId): Flow<RunRecord?> =
        openRuns.getOrPut(threadId.value) { MutableStateFlow(null) }

    override fun observeActive(): StateFlow<ActiveRunInfo?> = active.asStateFlow()

    override fun observeRunningThreads(): StateFlow<Set<ThreadId>> = MutableStateFlow(emptySet())

    override fun observeOpenRuns(): StateFlow<Map<ThreadId, RunRecord>> = MutableStateFlow(emptyMap())

    override fun observeRun(runId: RunId): Flow<RunRecord> = flowOf(record(runId.value, ThreadId("t")))

    override fun liveEvents(runId: RunId): Flow<AgentEvent> = emptyFlow()

    override suspend fun steer(
        runId: RunId,
        text: String,
    ): Outcome<Unit> = steerResult

    override fun cancel(runId: RunId) {
        cancelled += runId
    }

    override fun pause(runId: RunId): Unit = Unit

    override fun resume(
        runId: RunId,
        note: String?,
    ): Unit = Unit

    override suspend fun answer(
        runId: RunId,
        answer: String,
    ): Unit = Unit

    override suspend fun startChat(
        threadId: ThreadId?,
        text: String,
        images: List<ImageAttachment>,
        modelProfileId: String?,
        runMode: com.example.autop.core.common.RunMode,
        backgroundScreen: Boolean,
    ): Outcome<RunRecord> {
        chatBackgroundModes += backgroundScreen
        startGate?.await()
        return Outcome.Ok(record("r-new", threadId ?: ThreadId("thread-new")))
    }

    override suspend fun forkAndRun(
        threadId: ThreadId,
        atSeq: Long,
        text: String?,
    ): Outcome<RunRecord> = Outcome.Ok(record("r-fork", threadId))

    override suspend fun runTaskNow(task: Task): Outcome<RunRecord> = error("unused")

    override suspend fun runFlow(
        flowId: com.example.autop.core.common.FlowId,
        params: kotlinx.serialization.json.JsonObject,
        options: com.example.autop.flow.FlowRunOptions,
    ): Outcome<RunRecord> = error("unused")

    override suspend fun resumeInterrupted(runId: RunId): Outcome<RunRecord> = error("unused")

    override fun requeueWaiting(runId: RunId): Unit = Unit

    override fun snoozeWaiting(
        runId: RunId,
        minutes: Int,
    ): Unit = Unit

    override fun skipWaiting(runId: RunId): Unit = Unit

    override fun flowDebug(runId: RunId): com.example.autop.flow.FlowDebugControl? = null

    override fun flowProgress(runId: RunId): StateFlow<com.example.autop.flow.FlowProgress>? = null
}

/** fork 固定返回 `t2` 的 AgentSession 假件。 */
private class FakeForkSession : AgentSession {
    override fun start(
        request: com.example.autop.agent.RunRequest,
        scope: kotlinx.coroutines.CoroutineScope,
    ): com.example.autop.agent.RunHandle = error("unused")

    override suspend fun fork(
        threadId: ThreadId,
        atSeq: Long,
        title: String?,
    ): Outcome<ThreadId> = Outcome.Ok(ThreadId("t2"))
}

/** 无障碍已连接。 */
private class FakeChatAccessibility : AccessibilityStatus {
    override val connected: StateFlow<Boolean> = MutableStateFlow(true)
}

/** 日志文件空转。 */
private object FakeChatLogFiles : LogFiles {
    override val directoryPath: String = "/tmp"

    override fun append(line: String): Unit = Unit

    override fun totalBytes(): Long = 0

    override fun readAll(): String = ""

    override fun clear(): Long = 0
}

/** 模型配置假实现：没有任何模型配置（会话仍可正常发送，覆盖为空）。 */
private object FakeChatModelProfiles : com.example.autop.llm.ModelProfileStore {
    override val profiles: kotlinx.coroutines.flow.Flow<List<com.example.autop.llm.ModelProfile>> =
        kotlinx.coroutines.flow.flowOf(emptyList())
    override val defaultProfileId: kotlinx.coroutines.flow.Flow<String?> = kotlinx.coroutines.flow.flowOf(null)

    override suspend fun get(id: String): com.example.autop.llm.ModelProfile? = null

    override suspend fun upsert(profile: com.example.autop.llm.ModelProfile) = Unit

    override suspend fun delete(id: String) = Unit

    override suspend fun setDefaultProfileId(id: String?) = Unit
}

/** 测试用语音输入：默认不可用，不产生识别结果。 */
private class FakeSpeechInput : com.example.autop.device.SpeechInput {
    override fun available(): Boolean = false

    override val listening: kotlinx.coroutines.flow.StateFlow<Boolean> =
        kotlinx.coroutines.flow.MutableStateFlow(false)
    override val level: kotlinx.coroutines.flow.StateFlow<Float> =
        kotlinx.coroutines.flow.MutableStateFlow(0f)
    override val partial: kotlinx.coroutines.flow.StateFlow<String> =
        kotlinx.coroutines.flow.MutableStateFlow("")
    override val results: kotlinx.coroutines.flow.SharedFlow<String> =
        kotlinx.coroutines.flow.MutableSharedFlow()
    override val errors: kotlinx.coroutines.flow.SharedFlow<String> =
        kotlinx.coroutines.flow.MutableSharedFlow()

    override fun start() = Unit

    override fun stop() = Unit

    override fun cancel() = Unit
}
