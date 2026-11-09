/**
 * [AppearanceSettingsViewModel] 单测（docs/standards/coding-standards §8：ViewModel
 * 覆盖动作→配置写入、状态随配置更新、一次性通知三类行为）。
 */
package com.example.autop.feature.settings.me

import android.content.ContextWrapper
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.PermissionChecks
import com.example.autop.core.common.RunId
import com.example.autop.core.common.TaskId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakeModelProfileStore
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.device.AccessibilityStatus
import com.example.autop.device.NotificationFeed
import com.example.autop.device.NotificationInfo
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import com.example.autop.device.UpdateChecker
import com.example.autop.device.UpdateInfo
import com.example.autop.device.UpdateState
import com.example.autop.feature.settings.SettingsSection
import com.example.autop.llm.ModelRoles
import com.example.autop.memory.MemoryNode
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryStore
import com.example.autop.memory.MemoryVersion
import com.example.autop.memory.MemoryWrite
import com.example.autop.memory.WriteResult
import com.example.autop.task.AgentHostStatus
import com.example.autop.task.RunRecord
import com.example.autop.task.Task
import com.example.autop.task.TaskRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceSettingsViewModelTest {
    private val provider = FakeConfigProvider()
    private val editor = RecordingConfigEditor()
    private val store = FakeModelProfileStore()
    private val logger = RecordingLogger()
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `each appearance action writes its config path`() =
        runTest(testDispatcher) {
            val cases =
                listOf(
                    MeAction.SetDarkMode("DARK") to ("ui.appearance.darkMode" to JsonPrimitive("DARK")),
                    MeAction.SetDynamicColor(true) to ("ui.appearance.dynamicColor" to JsonPrimitive(true)),
                    MeAction.SetSeedIndex(3) to ("ui.appearance.seedIndex" to JsonPrimitive(3)),
                    MeAction.SetFontScale(1.15) to ("ui.appearance.fontScale" to JsonPrimitive(1.15)),
                    MeAction.SetIconScale(0.9) to ("ui.appearance.iconScale" to JsonPrimitive(0.9)),
                    MeAction.SetDensity("COMPACT") to ("ui.appearance.density" to JsonPrimitive("COMPACT")),
                    MeAction.SetMotionEnabled(false) to ("ui.appearance.motionEnabled" to JsonPrimitive(false)),
                    MeAction.SetShieldEnabled(false) to ("device.touch.shieldEnabled" to JsonPrimitive(false)),
                )
            for ((action, expected) in cases) {
                val viewModel = createViewModel()
                viewModel.onAction(action)
                assertThat(editor.writes).containsExactly(expected)
                editor.writes.clear()
            }
        }

    @Test
    fun `open section without page shows placeholder notice and dismiss clears it`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            assertThat(viewModel.uiState.value.notice).isNull()
            // TASKS 入口未落地（PERMISSIONS 已接健康检查页，不弹占位提示）。
            viewModel.onAction(MeAction.OpenSection(SettingsSection.TASKS))
            assertThat(viewModel.uiState.value.notice).isEqualTo(NoticeKind.PLACEHOLDER)
            viewModel.onAction(MeAction.DismissNotice)
            assertThat(viewModel.uiState.value.notice).isNull()
        }

    @Test
    fun `sections with pages open without placeholder notice`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(MeAction.OpenSection(SettingsSection.MODEL))
            viewModel.onAction(MeAction.OpenSection(SettingsSection.EMBEDDING))
            assertThat(viewModel.uiState.value.notice).isNull()
        }

    @Test
    fun `main model name comes from assigned role`() =
        runTest(testDispatcher) {
            store.upsert(testProfile("p1", displayName = "主力"))
            store.setRoles(ModelRoles(main = "p1", vision = null, light = null))
            val viewModel = createViewModel()
            assertThat(viewModel.uiState.value.mainModelName).isEqualTo("主力")
        }

    @Test
    fun `embedding provider id reflects llm config`() =
        runTest(testDispatcher) {
            provider.setOverrides(
                buildJsonObject {
                    put("llm", buildJsonObject { put("embedding", buildJsonObject { put("provider", "openai") }) })
                },
            )
            val viewModel = createViewModel()
            assertThat(viewModel.uiState.value.embeddingProviderId).isEqualTo("openai")
        }

    @Test
    fun `config updates flow into ui state immediately`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            assertThat(viewModel.uiState.value.appearance.darkMode).isEqualTo("SYSTEM")
            assertThat(viewModel.uiState.value.bannerDurationMs).isEqualTo(3000L)
            provider.setOverrides(
                buildJsonObject {
                    put(
                        "ui",
                        buildJsonObject {
                            put("appearance", buildJsonObject { put("darkMode", "DARK") })
                            put("banner", buildJsonObject { put("durationMs", 4500) })
                        },
                    )
                },
            )
            val state = viewModel.uiState.value
            assertThat(state.appearance.darkMode).isEqualTo("DARK")
            assertThat(state.bannerDurationMs).isEqualTo(4500L)
        }

    @Test
    fun `write failure is logged with path and code`() =
        runTest(testDispatcher) {
            editor.result = Outcome.Err(AppError(code = "config.invalid", message = "boom"))
            val viewModel = createViewModel()
            viewModel.onAction(MeAction.SetSeedIndex(2))
            val (event, kv) = logger.errors.single()
            assertThat(event).isEqualTo("appearance_write_failed")
            assertThat(kv["path"]).isEqualTo("ui.appearance.seedIndex")
            assertThat(kv["code"]).isEqualTo("config.invalid")
        }

    @Test
    fun `notification listener state follows system grant and rebinds when disconnected`() =
        runTest(testDispatcher) {
            val checks = FakePermissionChecks()
            val feed = FakeNotificationFeed()
            val viewModel = createViewModel(checks = checks, feed = feed)
            // 授权在而服务未连接：健康卡应算已授权，refresh 顺带请求重绑（N190）。
            checks.readNotifications = true
            viewModel.refresh()
            assertThat(viewModel.uiState.value.notificationListenerGranted).isTrue()
            assertThat(feed.rebindRequested).isTrue()
        }

    private fun createViewModel(
        checks: FakePermissionChecks = FakePermissionChecks(),
        feed: FakeNotificationFeed = FakeNotificationFeed(),
    ): AppearanceSettingsViewModel =
        AppearanceSettingsViewModel(
            configProvider = provider,
            configEditor = editor,
            store = store,
            accessibilityStatus = FakeAccessibility(),
            notificationFeed = feed,
            shellInput = FakeShellInput(),
            permissionChecks = checks,
            hostStatus = FakeHostStatus(),
            updateChecker = FakeUpdateChecker(),
            memoryStore = UnusedMemoryStore(),
            taskRepository = UnusedTaskRepository(),
            context = ContextWrapper(null),
            clock = FakeClock(),
            dispatchers = TestDispatcherProvider(testDispatcher),
            logger = logger,
        )
}

private class FakeAccessibility : AccessibilityStatus {
    override val connected: StateFlow<Boolean> = MutableStateFlow(false)
}

private class FakeNotificationFeed : NotificationFeed {
    override val posted: SharedFlow<NotificationInfo> = MutableSharedFlow()
    override val connected: StateFlow<Boolean> = MutableStateFlow(false)
    var rebindRequested = false

    override fun requestRebind() {
        rebindRequested = true
    }

    override suspend fun active(packageName: String?): List<NotificationInfo> = emptyList()

    override suspend fun recent(
        packageName: String?,
        sinceMs: Long?,
        limit: Int,
    ): List<NotificationInfo> = emptyList()
}

private class FakeHostStatus : AgentHostStatus {
    override val running: StateFlow<Boolean> = MutableStateFlow(false)
}

/** 本用例只读 shell 状态（健康卡），注入动作保持空实现。 */
private class FakeShellInput : ShellInput {
    override val state: StateFlow<ShellState> = MutableStateFlow(ShellState.Disconnected)

    override val paired: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun pair(
        pairingPort: Int,
        pairCode: String,
    ): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun connect(): Boolean = true

    override suspend fun tap(
        x: Int,
        y: Int,
        durationMs: Long,
    ): Boolean = true

    override suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ): Boolean = true

    override suspend fun keyEvent(code: Int): Boolean = true

    override suspend fun keyCombination(vararg codes: Int): Boolean = true
}

private class FakePermissionChecks : PermissionChecks {
    var readNotifications = false

    override fun canDrawOverlay(): Boolean = true

    override fun canPostNotifications(): Boolean = true

    override fun canReadNotifications(): Boolean = readNotifications

    override fun canUseFullScreenIntent(): Boolean = true

    override fun isIgnoringBatteryOptimizations(): Boolean = true

    override fun canScheduleExactAlarms(): Boolean = true
}

/** 本用例不触碰记忆存储（仅构造注入），保持空实现。 */
private class UnusedMemoryStore : MemoryStore {
    override suspend fun get(path: MemoryPath): MemoryNode? = error("unused")

    override suspend fun children(
        path: MemoryPath,
        offset: Int,
        limit: Int,
    ): List<MemoryNode> = emptyList()

    override suspend fun write(w: MemoryWrite): Outcome<WriteResult> = error("unused")

    override suspend fun delete(
        path: MemoryPath,
        recursive: Boolean,
        source: MemorySource,
        runId: RunId?,
    ): Outcome<Int> = error("unused")

    override suspend fun move(
        from: MemoryPath,
        to: MemoryPath,
    ): Outcome<Unit> = error("unused")

    override suspend fun versions(
        path: MemoryPath,
        limit: Int,
    ): List<MemoryVersion> = emptyList()

    override suspend fun revert(versionId: Long): Outcome<WriteResult> = error("unused")

    override suspend fun recentChanges(
        offset: Int,
        limit: Int,
        source: MemorySource?,
    ): List<MemoryVersion> = emptyList()

    override suspend fun changesOfRun(runId: RunId): List<MemoryVersion> = emptyList()

    override suspend fun revertRun(runId: RunId): Outcome<Int> = error("unused")

    override fun observeTree(): Flow<Unit> = flowOf(Unit)

    override suspend fun markConfirmed(path: MemoryPath) = Unit
}

/** 本用例不触碰任务仓库（仅构造注入），保持空实现。 */
private class UnusedTaskRepository : TaskRepository {
    override fun observeTasks(): Flow<List<Task>> = flowOf(emptyList())

    override suspend fun get(id: TaskId): Task? = null

    override suspend fun save(task: Task): Outcome<Task> = error("unused")

    override suspend fun delete(id: TaskId) = Unit

    override suspend fun setEnabled(
        id: TaskId,
        enabled: Boolean,
    ) = Unit

    override fun observeRuns(
        taskId: TaskId?,
        limit: Int,
    ): Flow<List<RunRecord>> = flowOf(emptyList())
}

/** 本用例不触碰更新检测（仅构造注入），保持空闲实现。 */
private class FakeUpdateChecker : UpdateChecker {
    override val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle)

    override fun start(scope: CoroutineScope) = Unit

    override suspend fun checkNow(): Outcome<UpdateInfo?> = Outcome.Ok(null)

    override suspend fun download(): Outcome<java.io.File> = error("unused")

    override fun install(): Outcome<Unit> = error("unused")
}
