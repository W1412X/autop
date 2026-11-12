/**
 * [OnboardingViewModel] 单测（docs/tech/08 §4.7）：卡片顺序与必需项、
 * 能力/机型闸门（全屏提醒、厂商后台）、"开始使用"写 `ui.onboarding.completed`
 * （写失败也放行）、ON_RESUME refresh 重新检测。
 */
package com.example.autop.feature.onboarding

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Capability
import com.example.autop.core.common.DeviceQuirks
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.PermissionChecks
import com.example.autop.core.common.PlatformCapabilities
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.testing.FakeModelProfileStore
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.device.AccessibilityStatus
import com.example.autop.device.NotificationFeed
import com.example.autop.device.NotificationInfo
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val editor = RecordingConfigEditor()
    private val logger = RecordingLogger()
    private val checks = FakePermissionChecks()
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
    fun `checklist keeps doc order with model and accessibility required`() =
        runTest(testDispatcher) {
            val state = createViewModel().uiState.value
            assertThat(state.items.map { it.id })
                .containsExactlyElementsIn(CheckId.entries)
                .inOrder()
            assertThat(state.items.filter { it.required }.map { it.id })
                .containsExactly(CheckId.MODEL, CheckId.ACCESSIBILITY)
                .inOrder()
            assertThat(state.requiredDone).isFalse()
        }

    @Test
    fun `full screen intent card gated by capability`() =
        runTest(testDispatcher) {
            val hidden = createViewModel(capabilities = emptySet()).uiState.value
            assertThat(hidden.items.first { it.id == CheckId.FULL_SCREEN_INTENT }.visible).isFalse()

            val shown =
                createViewModel(capabilities = setOf(Capability.FULL_SCREEN_INTENT_NEEDS_GRANT))
                    .uiState
                    .value
            assertThat(shown.items.first { it.id == CheckId.FULL_SCREEN_INTENT }.visible).isTrue()
        }

    @Test
    fun `vendor background card gated by quirk`() =
        runTest(testDispatcher) {
            val hidden = createViewModel(quirks = emptyMap()).uiState.value
            assertThat(hidden.vendorComponentSpec).isNull()
            assertThat(hidden.items.first { it.id == CheckId.VENDOR_BACKGROUND }.visible).isFalse()

            val spec = "component:com.vendor/.AutoStartActivity"
            val shown = createViewModel(quirks = mapOf("autostartIntent" to spec)).uiState.value
            assertThat(shown.vendorComponentSpec).isEqualTo(spec)
            assertThat(shown.items.first { it.id == CheckId.VENDOR_BACKGROUND }.visible).isTrue()
        }

    @Test
    fun `complete writes completed flag and marks done`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(OnboardingAction.Complete)
            val (path, written) = editor.writes.single()
            assertThat(path).isEqualTo("ui.onboarding.completed")
            assertThat(written).isEqualTo(JsonPrimitive(true))
            assertThat(viewModel.uiState.value.completed).isTrue()
        }

    @Test
    fun `complete passes through when write fails`() =
        runTest(testDispatcher) {
            editor.result = Outcome.Err(AppError(code = "config.invalid", message = "denied"))
            val viewModel = createViewModel()
            viewModel.onAction(OnboardingAction.Complete)
            assertThat(viewModel.uiState.value.completed).isTrue()
            assertThat(logger.errors.map { it.first }).contains("onboarding_complete_failed")
        }

    @Test
    fun `refresh re-evaluates permission checks`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            assertThat(
                viewModel.uiState.value.items
                    .first { it.id == CheckId.OVERLAY }
                    .done,
            ).isFalse()

            checks.overlay = true
            viewModel.refresh()
            assertThat(
                viewModel.uiState.value.items
                    .first { it.id == CheckId.OVERLAY }
                    .done,
            ).isTrue()
        }

    @Test
    fun `notification listener check follows system grant not connection`() =
        runTest(testDispatcher) {
            // 授权在而监听服务未连接（connected=false）也算完成，不再误报无权限（N190）。
            checks.readNotifications = true
            val viewModel = createViewModel()
            assertThat(
                viewModel.uiState.value.items
                    .first { it.id == CheckId.NOTIFICATION_LISTENER }
                    .done,
            ).isTrue()

            checks.readNotifications = false
            viewModel.refresh()
            assertThat(
                viewModel.uiState.value.items
                    .first { it.id == CheckId.NOTIFICATION_LISTENER }
                    .done,
            ).isFalse()
        }

    @Test
    fun `refresh requests rebind when granted but disconnected`() =
        runTest(testDispatcher) {
            checks.readNotifications = true
            val feed = FakeNotificationFeed()
            val viewModel = createViewModel(feed = feed)
            viewModel.refresh()
            assertThat(feed.rebindRequested).isTrue()
        }

    private fun createViewModel(
        capabilities: Set<Capability> = emptySet(),
        quirks: Map<String, String> = emptyMap(),
        feed: FakeNotificationFeed = FakeNotificationFeed(),
    ): OnboardingViewModel =
        OnboardingViewModel(
            configEditor = editor,
            permissionChecks = checks,
            capabilities = FakeCapabilities(capabilities),
            quirks = FakeQuirks(quirks),
            accessibilityStatus = FakeAccessibility(),
            notificationFeed = feed,
            modelProfiles = FakeModelProfileStore(),
            dispatchers = TestDispatcherProvider(testDispatcher),
            logger = logger,
        )
}

private class FakePermissionChecks : PermissionChecks {
    var overlay = false
    var notifications = true
    var readNotifications = false
    var fullScreenIntent = true
    var batteryOptimizations = false

    override fun canDrawOverlay(): Boolean = overlay

    override fun canPostNotifications(): Boolean = notifications

    override fun canReadNotifications(): Boolean = readNotifications

    override fun canUseFullScreenIntent(): Boolean = fullScreenIntent

    override fun isIgnoringBatteryOptimizations(): Boolean = batteryOptimizations

    override fun canScheduleExactAlarms(): Boolean = true
}

private class FakeCapabilities(
    private val values: Set<Capability>,
) : PlatformCapabilities {
    override fun has(c: Capability): Boolean = c in values
}

private class FakeQuirks(
    private val values: Map<String, String>,
) : DeviceQuirks {
    override fun value(key: String): String? = values[key]
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

private class RecordingConfigEditor : ConfigEditor {
    var result: Outcome<Unit> = Outcome.Ok(Unit)
    val writes = mutableListOf<Pair<String, JsonElement>>()

    override suspend fun setOverride(
        path: String,
        value: JsonElement,
    ): Outcome<Unit> {
        writes += path to value
        return result
    }

    override suspend fun clearOverride(path: String) {
        writes += path to JsonNull
    }

    override suspend fun userOverrides(): JsonObject = JsonObject(emptyMap())
}

private class RecordingLogger : Logger {
    val errors = mutableListOf<Pair<String, Map<String, Any?>>>()

    override fun d(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) = Unit

    override fun i(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) = Unit

    override fun w(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) = Unit

    override fun e(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) {
        errors += event to kv.toMap()
    }
}
