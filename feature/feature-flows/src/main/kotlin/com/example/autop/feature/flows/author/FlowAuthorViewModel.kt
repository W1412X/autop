/**
 * AI 创建流程 ViewModel：把创作会话（flow-api 的 [FlowAuthorSession]）接到界面。
 */
package com.example.autop.feature.flows.author

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.Outcome
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.feature.flows.AUTHOR_DRAFT_ARG
import com.example.autop.feature.flows.AUTHOR_EDIT_ARG
import com.example.autop.feature.flows.FlowsNavigator
import com.example.autop.feature.flows.NO_ARG
import com.example.autop.flow.AuthorState
import com.example.autop.flow.FlowAuthor
import com.example.autop.flow.FlowAuthorSession
import com.example.autop.llm.ModelProfileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 创作页状态。 */
public data class FlowAuthorUiState(
    /** 会话级模型覆盖（null = 跟随默认）与可选模型。 */
    val modelProfileId: String? = null,
    val modelOptions: List<OptionItem> = emptyList(),
    /** 全局默认模型 id（芯片跟随默认时显示其名称与"默认"标记）。 */
    val defaultModelId: String? = null,
    val ready: Boolean = false,
    val author: AuthorState = AuthorState(),
    val input: String = "",
    val saveError: String? = null,
)

/** 创作页意图。 */
public sealed interface FlowAuthorAction {
    public data object Back : FlowAuthorAction

    public data class SetInput(
        val value: String,
    ) : FlowAuthorAction

    public data object Send : FlowAuthorAction

    public data class Answer(
        val text: String,
    ) : FlowAuthorAction

    public data class Demonstration(
        val allowed: Boolean,
    ) : FlowAuthorAction

    public data object Stop : FlowAuthorAction

    public data object Save : FlowAuthorAction

    public data class SetModel(
        val profileId: String?,
    ) : FlowAuthorAction

    public data object OpenAdvanced : FlowAuthorAction
}

/** 创作页 ViewModel。 */
@HiltViewModel
public class FlowAuthorViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val author: FlowAuthor,
        private val modelProfiles: ModelProfileStore,
    ) : ViewModel() {
        private val draftId: String? = savedStateHandle.get<String>(AUTHOR_DRAFT_ARG)?.takeIf { it != NO_ARG }
        private val editId: String? = savedStateHandle.get<String>(AUTHOR_EDIT_ARG)?.takeIf { it != NO_ARG }
        private val _uiState = MutableStateFlow(FlowAuthorUiState())
        public val uiState: StateFlow<FlowAuthorUiState> = _uiState
        private var session: FlowAuthorSession? = null
        private var sendJob: Job? = null

        init {
            viewModelScope.launch {
                combine(modelProfiles.profiles, modelProfiles.defaultProfileId) { profiles, defaultId ->
                    profiles to defaultId
                }.collect { (profiles, defaultId) ->
                    _uiState.update {
                        it.copy(
                            modelOptions = profiles.map { p -> OptionItem(id = p.id, label = p.displayName) },
                            defaultModelId = defaultId,
                        )
                    }
                }
            }
            viewModelScope.launch {
                val s = author.open(draftId, editId)
                session = s
                _uiState.update { it.copy(ready = true) }
                s.state.collect { st -> _uiState.update { it.copy(author = st) } }
            }
        }

        public fun onAction(
            action: FlowAuthorAction,
            navigator: FlowsNavigator,
        ) {
            val s = session
            when (action) {
                FlowAuthorAction.Back -> navigator.navigateBack()
                is FlowAuthorAction.SetInput -> _uiState.update { it.copy(input = action.value) }
                FlowAuthorAction.Send -> send(s)
                is FlowAuthorAction.Answer -> s?.answer(action.text)
                is FlowAuthorAction.Demonstration -> s?.confirmDemonstration(action.allowed)
                FlowAuthorAction.Stop -> {
                    s?.cancel()
                    sendJob?.cancel()
                }
                FlowAuthorAction.Save -> save(s, navigator)
                is FlowAuthorAction.SetModel -> _uiState.update { it.copy(modelProfileId = action.profileId) }
                FlowAuthorAction.OpenAdvanced -> save(s, navigator, openEditor = true)
            }
        }

        private fun send(s: FlowAuthorSession?) {
            val text = _uiState.value.input.trim()
            if (s == null || text.isEmpty()) return
            // 有待回答的问题时，输入框的内容就是回答。
            if (_uiState.value.author.pendingQuestion != null) {
                s.answer(text)
                _uiState.update { it.copy(input = "") }
                return
            }
            if (_uiState.value.author.busy) return
            _uiState.update { it.copy(input = "") }
            sendJob = viewModelScope.launch { s.send(text, _uiState.value.modelProfileId) }
        }

        private fun save(
            s: FlowAuthorSession?,
            navigator: FlowsNavigator,
            openEditor: Boolean = false,
        ) {
            if (s == null) return
            viewModelScope.launch {
                when (val saved = s.save()) {
                    is Outcome.Ok ->
                        if (openEditor) {
                            navigator.openEditor(
                                saved.value.id.value,
                            )
                        } else {
                            navigator.openFlow(saved.value.id.value)
                        }
                    is Outcome.Err -> _uiState.update { it.copy(saveError = saved.error.message) }
                }
            }
        }

        override fun onCleared() {
            sendJob?.cancel()
            super.onCleared()
        }
    }
