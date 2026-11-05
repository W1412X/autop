/**
 * [AdvancedViewModel] 单测（docs/tech/08 §4.5"高级配置"）：配置段分组列出、
 * 编辑整段 JSON 经 `ConfigEditor.setOverride` 写覆盖；解析失败/写入失败显示错误且不生效。
 */
package com.example.autop.feature.settings.me.advanced

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.AllConfigKeys
import com.example.autop.core.config.UiConfig
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.feature.settings.me.RecordingConfigEditor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedViewModelTest {
    private val provider = FakeConfigProvider()
    private val editor = RecordingConfigEditor()
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
    fun `groups cover all config keys sorted by domain`() =
        runTest(testDispatcher) {
            val state = createViewModel().uiState.value
            val items = state.groups.flatMap { it.items }
            assertThat(items.map { it.path })
                .containsExactlyElementsIn(AllConfigKeys.ALL.map { it.path })
            assertThat(state.groups.map { it.domain }).isInOrder()
        }

    @Test
    fun `open shows overridden value`() =
        runTest(testDispatcher) {
            provider.setOverrides(
                buildJsonObject {
                    put(
                        "ui",
                        buildJsonObject {
                            put(
                                "onboarding",
                                buildJsonObject { put("completed", true) },
                            )
                        },
                    )
                },
            )
            val viewModel = createViewModel()
            viewModel.onAction(AdvancedAction.Open(UiConfig.ONBOARDING.path))
            val editing = viewModel.uiState.value.editing
            assertThat(editing).isNotNull()
            assertThat(editing!!.overridden).isTrue()
            assertThat(editing.valueJson).contains("true")
        }

    @Test
    fun `save with invalid json shows error and writes nothing`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(AdvancedAction.Open(UiConfig.ONBOARDING.path))
            viewModel.onAction(AdvancedAction.SetEditText("{broken"))
            viewModel.onAction(AdvancedAction.Save)
            assertThat(viewModel.uiState.value.editError).isNotNull()
            assertThat(editor.writes).isEmpty()
        }

    @Test
    fun `save writes override and closes editor`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(AdvancedAction.Open(UiConfig.ONBOARDING.path))
            viewModel.onAction(AdvancedAction.SetEditText("true"))
            viewModel.onAction(AdvancedAction.Save)
            val (path, written) = editor.writes.single()
            assertThat(path).isEqualTo(UiConfig.ONBOARDING.path)
            assertThat(written).isEqualTo(JsonPrimitive(true))
            assertThat(viewModel.uiState.value.editing).isNull()
        }

    @Test
    fun `write failure shows error`() =
        runTest(testDispatcher) {
            editor.result = Outcome.Err(AppError(code = "config.invalid", message = "bad section"))
            val viewModel = createViewModel()
            viewModel.onAction(AdvancedAction.Open(UiConfig.ONBOARDING.path))
            viewModel.onAction(AdvancedAction.SetEditText("true"))
            viewModel.onAction(AdvancedAction.Save)
            assertThat(viewModel.uiState.value.editError).isEqualTo("bad section")
            assertThat(viewModel.uiState.value.editing).isNotNull()
        }

    @Test
    fun `clear override records clear`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(AdvancedAction.Open(UiConfig.ONBOARDING.path))
            viewModel.onAction(AdvancedAction.ClearOverride)
            assertThat(editor.writes.single()).isEqualTo(UiConfig.ONBOARDING.path to JsonNull)
        }

    private fun createViewModel() = AdvancedViewModel(provider, editor, TestDispatcherProvider(testDispatcher))
}
