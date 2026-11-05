/**
 * [BudgetViewModel] 单测（docs/standards/coding-standards §8：ViewModel
 * 覆盖动作→配置写入、状态随配置更新、非法输入不写入三类行为）。
 */
package com.example.autop.feature.settings.me.budget

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.feature.settings.me.RecordingConfigEditor
import com.example.autop.feature.settings.me.RecordingLogger
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
class BudgetViewModelTest {
    private val provider = FakeConfigProvider()
    private val editor = RecordingConfigEditor()
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
    fun `saving each field writes its config path in storage units`() =
        runTest(testDispatcher) {
            val cases =
                listOf(
                    Triple(BudgetField.MAX_STEPS, "30", "agent.budget.maxSteps" to JsonPrimitive(30L)),
                    Triple(
                        BudgetField.MAX_TOTAL_TOKENS,
                        "800000",
                        "agent.budget.maxTotalTokens" to JsonPrimitive(800000L),
                    ),
                    // 时长按分钟编辑，存储毫秒
                    Triple(BudgetField.MAX_DURATION_MS, "15", "agent.budget.maxDurationMs" to JsonPrimitive(900000L)),
                    Triple(BudgetField.MAX_SCREENSHOTS, "9", "agent.budget.maxScreenshots" to JsonPrimitive(9L)),
                )
            for ((field, text, expected) in cases) {
                val viewModel = createViewModel()
                viewModel.onAction(BudgetAction.OpenEditor(field))
                viewModel.onAction(BudgetAction.SetEditText(text))
                viewModel.onAction(BudgetAction.Save)
                assertThat(editor.writes).containsExactly(expected)
                editor.writes.clear()
            }
        }

    @Test
    fun `unlimited toggle saves zero`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(BudgetAction.OpenEditor(BudgetField.MAX_STEPS))
            viewModel.onAction(BudgetAction.SetUnlimited(true))
            viewModel.onAction(BudgetAction.Save)

            assertThat(editor.writes).containsExactly("agent.budget.maxSteps" to JsonPrimitive(0L))
            assertThat(viewModel.uiState.value.editing).isNull()
        }

    @Test
    fun `invalid input is flagged and nothing is written`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            for (text in listOf("abc", "-5", "")) {
                viewModel.onAction(BudgetAction.OpenEditor(BudgetField.MAX_STEPS))
                viewModel.onAction(BudgetAction.SetEditText(text))
                viewModel.onAction(BudgetAction.Save)
                val editing = viewModel.uiState.value.editing
                assertThat(editing?.invalid).isTrue()
            }
            assertThat(editor.writes).isEmpty()
        }

    @Test
    fun `restore default clears the override`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(BudgetAction.OpenEditor(BudgetField.MAX_TOTAL_TOKENS))
            viewModel.onAction(BudgetAction.RestoreDefault)

            assertThat(editor.writes).containsExactly("agent.budget.maxTotalTokens" to JsonNull)
            assertThat(viewModel.uiState.value.editing).isNull()
        }

    @Test
    fun `zero value opens editor in unlimited mode`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(BudgetAction.OpenEditor(BudgetField.MAX_DURATION_MS))

            val editing = viewModel.uiState.value.editing
            assertThat(editing?.unlimited).isTrue()
            assertThat(editing?.text).isEmpty()
        }

    @Test
    fun `stored value opens editor prefilled in edit units`() =
        runTest(testDispatcher) {
            provider.setOverrides(
                buildJsonObject {
                    put("agent", buildJsonObject { put("budget", buildJsonObject { put("maxDurationMs", 900000) }) })
                },
            )
            val viewModel = createViewModel()
            viewModel.onAction(BudgetAction.OpenEditor(BudgetField.MAX_DURATION_MS))

            val editing = viewModel.uiState.value.editing
            assertThat(editing?.unlimited).isFalse()
            assertThat(editing?.text).isEqualTo("15")
        }

    @Test
    fun `config updates flow into ui state`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            val initialItems = viewModel.uiState.value.items
            val initial = initialItems.map { it.value }
            assertThat(initial).containsExactly(0L, 0L, 0L, 0L).inOrder()
            provider.setOverrides(
                buildJsonObject {
                    put(
                        "agent",
                        buildJsonObject {
                            put("budget", buildJsonObject { put("maxSteps", 40) })
                        },
                    )
                },
            )
            val updatedItems = viewModel.uiState.value.items
            assertThat(updatedItems.first().value).isEqualTo(40L)
        }

    @Test
    fun `write failure is logged with path and code`() =
        runTest(testDispatcher) {
            editor.result = Outcome.Err(AppError(code = "config.invalid", message = "boom"))
            val viewModel = createViewModel()
            viewModel.onAction(BudgetAction.OpenEditor(BudgetField.MAX_STEPS))
            viewModel.onAction(BudgetAction.SetEditText("10"))
            viewModel.onAction(BudgetAction.Save)

            val (event, kv) = logger.errors.single()
            assertThat(event).isEqualTo("budget_write_failed")
            assertThat(kv["path"]).isEqualTo("agent.budget.maxSteps")
            assertThat(kv["code"]).isEqualTo("config.invalid")
        }

    private fun createViewModel(): BudgetViewModel =
        BudgetViewModel(
            configProvider = provider,
            configEditor = editor,
            dispatchers = TestDispatcherProvider(testDispatcher),
            logger = logger,
        )
}
