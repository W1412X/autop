/**
 * [ObserveViewModel] 单测（docs/standards/coding-standards §8：ViewModel
 * 覆盖动作→配置写入、状态随配置更新、可用性展示三类行为）。
 */
package com.example.autop.feature.settings.me.observe

import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.device.ObserveMode
import com.example.autop.device.ObserveModeAvailability
import com.example.autop.feature.settings.me.RecordingConfigEditor
import com.example.autop.feature.settings.me.RecordingLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveViewModelTest {
    private val provider = FakeConfigProvider()
    private val editor = RecordingConfigEditor()
    private val logger = RecordingLogger()
    private val availability = FakeAvailability()
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
    fun `items follow priority order and mode switches`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()

            val items = viewModel.uiState.value.items
            assertThat(items.map { it.id }).containsExactly("a11y", "visual").inOrder()
            assertThat(items.map { it.enabled }).containsExactly(true, true)
        }

    @Test
    fun `toggling a mode writes device observe section`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()

            viewModel.onAction(ObserveAction.SetEnabled("visual", false))

            val (path, value) = editor.writes.single()
            assertThat(path).isEqualTo("device.observe")
            val modes = (value as JsonObject)["modes"] as JsonObject
            val enabled = (modes["visual"] as JsonObject)["enabled"]?.jsonPrimitive?.booleanOrNull
            assertThat(enabled).isFalse()
        }

    @Test
    fun `moving a mode rewrites priority order`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()

            viewModel.onAction(ObserveAction.MoveUp("visual"))

            val (_, value) = editor.writes.single()
            val priority = (value as JsonObject)["priority"] as JsonArray
            assertThat(priority.map { (it as JsonPrimitive).content })
                .containsExactly("visual", "a11y")
                .inOrder()
        }

    @Test
    fun `moving the first mode up writes nothing`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()

            viewModel.onAction(ObserveAction.MoveUp("a11y"))

            assertThat(editor.writes).isEmpty()
        }

    @Test
    fun `unavailable reason is exposed per mode`() =
        runTest(testDispatcher) {
            availability.reasons[ObserveMode.VISUAL] = "视觉模型未就绪"

            val viewModel = createViewModel()

            val items = viewModel.uiState.value.items
            assertThat(items.first { it.id == "visual" }.unavailableReason).isEqualTo("视觉模型未就绪")
            assertThat(items.first { it.id == "a11y" }.unavailableReason).isNull()
        }

    @Test
    fun `config updates flow into ui state`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            provider.setOverrides(
                buildJsonObject {
                    put(
                        "device",
                        buildJsonObject {
                            put(
                                "observe",
                                buildJsonObject {
                                    put(
                                        "priority",
                                        JsonArray(
                                            listOf(
                                                JsonPrimitive("a11y"),
                                                JsonPrimitive("visual"),
                                            ),
                                        ),
                                    )
                                },
                            )
                        },
                    )
                },
            )

            val items = viewModel.uiState.value.items
            val ids = items.map { it.id }
            assertThat(ids)
                .containsExactly("a11y", "visual")
                .inOrder()
        }

    private fun createViewModel(): ObserveViewModel =
        ObserveViewModel(
            configProvider = provider,
            configEditor = editor,
            availability = availability,
            dispatchers = TestDispatcherProvider(testDispatcher),
            logger = logger,
        )
}

/** 可编程的 [ObserveModeAvailability]。 */
private class FakeAvailability : ObserveModeAvailability {
    val reasons = mutableMapOf<ObserveMode, String?>()

    override fun unavailableReason(mode: ObserveMode): String? = reasons[mode]
}
