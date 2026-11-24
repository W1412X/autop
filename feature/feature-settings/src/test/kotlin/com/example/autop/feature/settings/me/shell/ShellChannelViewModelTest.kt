package com.example.autop.feature.settings.me.shell

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.config.DeviceBackgroundSection
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.device.PairingDiscovery
import com.example.autop.device.ShellExec
import com.example.autop.device.ShellExecResult
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellJobState
import com.example.autop.device.ShellState
import com.example.autop.feature.settings.me.RecordingConfigEditor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShellChannelViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val provider =
        FakeConfigProvider(
            buildJsonObject {
                put(
                    "device",
                    buildJsonObject {
                        put(
                            "background",
                            Json.encodeToJsonElement(
                                DeviceBackgroundSection(jpegQuality = 73, maxPayloadBytes = 4_194_304),
                            ),
                        )
                    },
                )
            },
        )
    private val editor = RecordingConfigEditor()
    private val shellInput = FakeShellInput()
    private val shellExec = FakeShellExec()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `saving background parameters preserves unexposed fields`() =
        runTest(dispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(ShellChannelAction.UpdateBackgroundConfig(width = "810"))
            viewModel.onAction(ShellChannelAction.UpdateBackgroundConfig(height = "1800"))
            viewModel.onAction(ShellChannelAction.UpdateBackgroundConfig(dpi = "420"))
            viewModel.onAction(ShellChannelAction.UpdateBackgroundConfig(launchTimeoutMs = "5000"))
            viewModel.onAction(ShellChannelAction.UpdateBackgroundConfig(requestTimeoutMs = "45000"))

            viewModel.onAction(ShellChannelAction.SaveBackgroundConfig)
            runCurrent()

            val (path, value) = editor.writes.single()
            val saved = Json.decodeFromJsonElement(DeviceBackgroundSection.serializer(), value)
            assertThat(path).isEqualTo(DeviceConfig.BACKGROUND.path)
            assertThat(saved.width).isEqualTo(810)
            assertThat(saved.height).isEqualTo(1800)
            assertThat(saved.dpi).isEqualTo(420)
            assertThat(saved.launchTimeoutMs).isEqualTo(5_000L)
            assertThat(saved.requestTimeoutMs).isEqualTo(45_000L)
            assertThat(saved.jpegQuality).isEqualTo(73)
            assertThat(saved.maxPayloadBytes).isEqualTo(4_194_304)
            assertThat(viewModel.uiState.value.notice).isEqualTo(ShellNotice.BackgroundSaved)
        }

    @Test
    fun `invalid background parameters are rejected without writing`() =
        runTest(dispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(ShellChannelAction.UpdateBackgroundConfig(width = "0"))
            viewModel.onAction(ShellChannelAction.SaveBackgroundConfig)
            assertThat(editor.writes).isEmpty()
            assertThat(viewModel.uiState.value.notice).isEqualTo(ShellNotice.BackgroundInvalid)

            viewModel.onAction(ShellChannelAction.UpdateBackgroundConfig(width = "720"))
            viewModel.onAction(ShellChannelAction.UpdateBackgroundConfig(dpi = "abc"))
            viewModel.onAction(ShellChannelAction.SaveBackgroundConfig)
            assertThat(editor.writes).isEmpty()
            assertThat(viewModel.uiState.value.notice).isEqualTo(ShellNotice.BackgroundInvalid)
        }

    @Test
    fun `stored background parameters populate the form`() =
        runTest(dispatcher) {
            provider.setOverrides(
                buildJsonObject {
                    put(
                        "device",
                        buildJsonObject {
                            put(
                                "background",
                                buildJsonObject {
                                    put("width", 900)
                                    put("height", 2000)
                                    put("dpi", 400)
                                    put("launchTimeoutMs", 6000)
                                    put("requestTimeoutMs", 50000)
                                },
                            )
                        },
                    )
                },
            )
            val viewModel = createViewModel()
            runCurrent()

            assertThat(viewModel.uiState.value.backgroundWidthText).isEqualTo("900")
            assertThat(viewModel.uiState.value.backgroundHeightText).isEqualTo("2000")
            assertThat(viewModel.uiState.value.backgroundDpiText).isEqualTo("400")
            assertThat(viewModel.uiState.value.backgroundLaunchTimeoutText).isEqualTo("6000")
            assertThat(viewModel.uiState.value.backgroundRequestTimeoutText).isEqualTo("50000")
        }

    private fun createViewModel(): ShellChannelViewModel =
        ShellChannelViewModel(
            shellInput = shellInput,
            discovery = FakePairingDiscovery,
            dispatchers = TestDispatcherProvider(dispatcher),
            configProvider = provider,
            configEditor = editor,
            shellExec = shellExec,
        )
}

private object FakePairingDiscovery : PairingDiscovery {
    override val discoveredPort = MutableStateFlow<Int?>(null)
    override val notificationShown = MutableStateFlow(false)

    override fun start(scope: CoroutineScope) = Unit

    override fun stop() = Unit

    override fun refresh() = Unit
}

private class FakeShellInput : ShellInput {
    override val state = MutableStateFlow<ShellState>(ShellState.Disconnected)
    override val paired = MutableStateFlow(false)

    override suspend fun pair(
        pairingPort: Int,
        pairCode: String,
    ): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun connect(): Boolean = false

    override suspend fun probe(): Boolean = false

    override suspend fun tap(
        x: Int,
        y: Int,
        durationMs: Long,
    ): Boolean = false

    override suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ): Boolean = false

    override suspend fun keyEvent(code: Int): Boolean = false

    override suspend fun keyCombination(vararg codes: Int): Boolean = false
}

private class FakeShellExec : ShellExec {
    override suspend fun exec(
        owner: RunId,
        command: String,
        timeoutMs: Long,
    ): Outcome<ShellExecResult> = Outcome.Ok(ShellExecResult(ShellJobState.DONE, 0, "", "", false, 0, 0))

    override suspend fun releaseRun(owner: RunId) = Unit

    override suspend fun closeAll() = Unit
}
