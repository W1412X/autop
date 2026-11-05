/**
 * [PromptsViewModel] 单测（docs/tech/08 §4.5"提示词"）：模板列表、
 * 编辑正文经 `PromptOverrideEditor.setOverride` 写覆盖（未声明变量显示错误），
 * 清除覆盖恢复内置正文。
 */
package com.example.autop.feature.settings.me.prompts

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptOverrideEditor
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.core.prompt.PromptSource
import com.example.autop.core.prompt.PromptTemplate
import com.example.autop.core.prompt.RenderedPrompt
import com.example.autop.core.testing.TestDispatcherProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PromptsViewModelTest {
    private val repository = FakePromptRepository(listOf(template("a"), template("b")))
    private val editor = FakePromptOverrideEditor(repository)
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
    fun `lists templates with version source and vars`() =
        runTest(testDispatcher) {
            val state = createViewModel().uiState.value
            assertThat(state.items.map { it.id }).containsExactly("a", "b").inOrder()
            assertThat(state.items.first().source).isEqualTo(PromptSource.BUILTIN)
            assertThat(state.items.first().vars).containsExactly("topic")
        }

    @Test
    fun `open pre-fills body and save records override`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(PromptsAction.Open("a"))
            assertThat(viewModel.uiState.value.editText).isEqualTo("body-a")
            viewModel.onAction(PromptsAction.SetEditText("body-a edited"))
            viewModel.onAction(PromptsAction.Save)
            assertThat(repository.overrides["a"]).isEqualTo("body-a edited")
            assertThat(viewModel.uiState.value.editing).isNull()
            assertThat(
                viewModel.uiState.value.items
                    .first { it.id == "a" }
                    .source,
            ).isEqualTo(PromptSource.USER)
        }

    @Test
    fun `save failure shows error and keeps editor open`() =
        runTest(testDispatcher) {
            editor.result = Outcome.Err(AppError(code = "prompt.undeclared_var", message = "undeclared var"))
            val viewModel = createViewModel()
            viewModel.onAction(PromptsAction.Open("a"))
            viewModel.onAction(PromptsAction.SetEditText("{{nope}}"))
            viewModel.onAction(PromptsAction.Save)
            assertThat(viewModel.uiState.value.editError).isEqualTo("undeclared var")
            assertThat(viewModel.uiState.value.editing).isNotNull()
        }

    @Test
    fun `clear override restores builtin body`() =
        runTest(testDispatcher) {
            repository.overrides["a"] = "custom"
            editor.result = Outcome.Ok(Unit)
            val viewModel = createViewModel()
            assertThat(
                viewModel.uiState.value.items
                    .first { it.id == "a" }
                    .source,
            ).isEqualTo(PromptSource.USER)
            viewModel.onAction(PromptsAction.Open("a"))
            viewModel.onAction(PromptsAction.ClearOverride)
            assertThat(editor.cleared).containsExactly("a")
            assertThat(
                viewModel.uiState.value.items
                    .first { it.id == "a" }
                    .source,
            ).isEqualTo(PromptSource.BUILTIN)
        }

    private fun createViewModel() = PromptsViewModel(repository, editor, TestDispatcherProvider(testDispatcher))
}

private fun template(id: String): PromptTemplate =
    PromptTemplate(
        id = id,
        version = 1,
        vars = setOf("topic"),
        optionalVars = emptySet(),
        meta = emptyMap(),
        body = "body-$id",
        source = PromptSource.BUILTIN,
    )

/** 内存版 [PromptRepository]（覆盖正文合并进 `all()`）。 */
private class FakePromptRepository(
    private val builtin: List<PromptTemplate>,
) : PromptRepository {
    val overrides = mutableMapOf<String, String>()

    override fun get(id: String): PromptTemplate = all().first { it.id == id }

    override fun render(
        id: String,
        vars: Map<String, String>,
    ): Outcome<RenderedPrompt> = Outcome.Ok(RenderedPrompt(id, 1, get(id).body))

    override fun all(): List<PromptTemplate> =
        builtin
            .map { template ->
                overrides[template.id]?.let { template.copy(body = it, source = PromptSource.USER) } ?: template
            }.sortedBy { it.id }
}

/** 记录覆盖写入的 [PromptOverrideEditor]（写入同步进 [repository]；[result] 可改为 Err 模拟校验失败）。 */
private class FakePromptOverrideEditor(
    private val repository: FakePromptRepository,
) : PromptOverrideEditor {
    var result: Outcome<Unit> = Outcome.Ok(Unit)
    val cleared = mutableListOf<String>()

    override suspend fun setOverride(
        id: String,
        body: String,
    ): Outcome<Unit> {
        if (result is Outcome.Err) return result
        repository.overrides[id] = body
        return Outcome.Ok(Unit)
    }

    override suspend fun clearOverride(id: String) {
        cleared += id
        repository.overrides.remove(id)
    }
}
