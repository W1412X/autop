/**
 * AI 创建任务 ViewModel：把创作会话（task-api 的 [TaskAuthorSession]）接到界面。
 */
package com.example.autop.feature.tasks.author

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.Outcome
import com.example.autop.feature.tasks.TasksNavigator
import com.example.autop.task.TaskAuthor
import com.example.autop.task.TaskAuthorSession
import com.example.autop.task.TaskAuthorState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 创作页状态。 */
public data class TaskAuthorUiState(
    val author: TaskAuthorState = TaskAuthorState(),
    val input: String = "",
    val saveError: String? = null,
)

/** 创作页意图。 */
public sealed interface TaskAuthorAction {
    public data object Back : TaskAuthorAction

    public data class SetInput(
        val value: String,
    ) : TaskAuthorAction

    public data object Send : TaskAuthorAction

    public data class Answer(
        val text: String,
    ) : TaskAuthorAction

    public data object Stop : TaskAuthorAction

    public data object Save : TaskAuthorAction
}

/** 创作页 ViewModel。 */
@HiltViewModel
public class TaskAuthorViewModel
    @Inject
    constructor(
        private val author: TaskAuthor,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(TaskAuthorUiState())
        public val uiState: StateFlow<TaskAuthorUiState> = _uiState
        private var session: TaskAuthorSession? = null
        private var sendJob: Job? = null

        init {
            viewModelScope.launch {
                val s = author.open()
                session = s
                s.state.collect { st -> _uiState.update { it.copy(author = st) } }
            }
        }

        public fun onAction(
            action: TaskAuthorAction,
            navigator: TasksNavigator,
        ) {
            val s = session
            when (action) {
                TaskAuthorAction.Back -> navigator.navigateBack()
                is TaskAuthorAction.SetInput -> _uiState.update { it.copy(input = action.value) }
                TaskAuthorAction.Send -> send(s)
                is TaskAuthorAction.Answer -> s?.answer(action.text)
                TaskAuthorAction.Stop -> {
                    s?.cancel()
                    sendJob?.cancel()
                }
                TaskAuthorAction.Save -> save(s, navigator)
            }
        }

        private fun send(s: TaskAuthorSession?) {
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
            sendJob = viewModelScope.launch { s.send(text) }
        }

        private fun save(
            s: TaskAuthorSession?,
            navigator: TasksNavigator,
        ) {
            if (s == null) return
            viewModelScope.launch {
                when (val saved = s.save()) {
                    is Outcome.Ok -> navigator.navigateBack()
                    is Outcome.Err -> _uiState.update { it.copy(saveError = saved.error.message) }
                }
            }
        }

        override fun onCleared() {
            sendJob?.cancel()
            super.onCleared()
        }
    }
