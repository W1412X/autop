/**
 * 提示词页 ViewModel（docs/tech/08 §4.5"提示词"）：
 * 列出 [PromptRepository.all] 全部模板（id/version/source/正文），
 * 编辑正文经 [PromptOverrideEditor.setOverride] 写用户覆盖（头部沿用内置）；
 * 未声明变量（`prompt.undeclared_var`）显示错误且不生效。
 */
package com.example.autop.feature.settings.me.prompts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptOverrideEditor
import com.example.autop.core.prompt.PromptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 编辑器瞬时状态（与模板列表组合成 [PromptsUiState]）。 */
private data class EditorState(
    val id: String? = null,
    val text: String = "",
    val error: String? = null,
    val saving: Boolean = false,
)

@HiltViewModel
public class PromptsViewModel
    @Inject
    constructor(
        private val repository: PromptRepository,
        private val overrideEditor: PromptOverrideEditor,
        private val dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val items = MutableStateFlow<List<PromptItem>>(emptyList())
        private val editor = MutableStateFlow(EditorState())

        public val uiState: StateFlow<PromptsUiState> =
            combine(items, editor) { list, ed ->
                PromptsUiState(
                    items = list,
                    editing = ed.id?.let { id -> list.firstOrNull { it.id == id } },
                    editText = ed.text,
                    editError = ed.error,
                    saving = ed.saving,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = PromptsUiState(),
            )

        init {
            refresh()
        }

        public fun onAction(action: PromptsAction) {
            when (action) {
                is PromptsAction.Open -> open(action.id)
                is PromptsAction.SetEditText -> editor.value = editor.value.copy(text = action.text, error = null)
                PromptsAction.Save -> save()
                PromptsAction.ClearOverride -> clearOverride()
                PromptsAction.CloseEditor -> editor.value = EditorState()
            }
        }

        private fun refresh() {
            viewModelScope.launch(dispatchers.io) {
                items.value =
                    repository.all().map { template ->
                        PromptItem(
                            id = template.id,
                            version = template.version,
                            source = template.source,
                            body = template.body,
                            vars = template.vars,
                        )
                    }
            }
        }

        private fun open(id: String) {
            val item = items.value.firstOrNull { it.id == id } ?: return
            editor.value = EditorState(id = id, text = item.body)
        }

        private fun save() {
            val state = editor.value
            val id = state.id ?: return
            viewModelScope.launch(dispatchers.io) {
                editor.value = state.copy(saving = true, error = null)
                when (val result = overrideEditor.setOverride(id, state.text)) {
                    is Outcome.Ok -> {
                        editor.value = EditorState()
                        refresh()
                    }
                    is Outcome.Err -> editor.value = editor.value.copy(saving = false, error = result.error.message)
                }
            }
        }

        private fun clearOverride() {
            val id = editor.value.id ?: return
            viewModelScope.launch(dispatchers.io) {
                overrideEditor.clearOverride(id)
                editor.value = EditorState()
                refresh()
            }
        }
    }
