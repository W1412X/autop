/**
 * AI 创建任务 ViewModel：把创作会话（task-api 的 [TaskAuthorSession]）接到界面。
 */
package com.example.autop.feature.tasks.author

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.TaskId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.joinVoiceText
import com.example.autop.device.SpeechInput
import com.example.autop.feature.tasks.NEW_TASK_ID
import com.example.autop.feature.tasks.TASK_HINT_ARG
import com.example.autop.feature.tasks.TASK_ID_ARG
import com.example.autop.feature.tasks.TasksNavigator
import com.example.autop.feature.tasks.flowsEnabled
import com.example.autop.llm.ModelProfileStore
import com.example.autop.task.TaskAuthor
import com.example.autop.task.TaskAuthorSession
import com.example.autop.task.TaskAuthorState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 创作页状态。 */
public data class TaskAuthorUiState(
    /** 流程 Beta 功能是否开启；关闭时示例与草稿不展示流程（ADR-0030）。 */
    val flowsEnabled: Boolean = false,
    /** 会话级模型覆盖（null = 跟随默认）与可选模型。 */
    val modelProfileId: String? = null,
    val modelOptions: List<com.example.autop.core.ui.component.OptionItem> = emptyList(),
    /** 全局默认模型 id（芯片跟随默认时显示其名称与"默认"标记）。 */
    val defaultModelId: String? = null,
    val author: TaskAuthorState = TaskAuthorState(),
    val input: String = "",
    val saveError: String? = null,
    /** 系统语音识别是否可用（不可用时不显示麦克风）。 */
    val voiceAvailable: Boolean = false,
    /** 正在语音输入。 */
    val listening: Boolean = false,
    /** 语音输入出错的错误码（提示用，见界面）。 */
    val voiceError: String? = null,
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

    public data class SetModel(
        val profileId: String?,
    ) : TaskAuthorAction

    public data object Save : TaskAuthorAction

    /** 开始语音输入（麦克风权限由界面先行取得）。 */
    public data object StartVoice : TaskAuthorAction

    /** 结束语音输入。 */
    public data object StopVoice : TaskAuthorAction

    /** 语音输入失败（如权限被拒），以错误码提示。 */
    public data class VoiceFailed(
        val code: String,
    ) : TaskAuthorAction
}

/** 创作页 ViewModel。 */
@HiltViewModel
public class TaskAuthorViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val author: TaskAuthor,
        private val modelProfiles: ModelProfileStore,
        private val configProvider: ConfigProvider,
        private val speech: SpeechInput,
    ) : ViewModel() {
        private val taskId: String? = savedStateHandle.get<String>(TASK_ID_ARG)?.takeIf { it != NEW_TASK_ID }
        private val _uiState =
            MutableStateFlow(
                TaskAuthorUiState(
                    flowsEnabled = configProvider.current().flowsEnabled(),
                    voiceAvailable = speech.available(),
                ),
            )
        public val uiState: StateFlow<TaskAuthorUiState> = _uiState
        private var session: TaskAuthorSession? = null
        private var sendJob: Job? = null

        /** 开始语音输入时输入框已有的文字；识别结果接在它后面。 */
        private var voiceBase: String = ""

        init {
            collectVoice()
            viewModelScope.launch {
                configProvider.observe().collect { snapshot ->
                    _uiState.update { it.copy(flowsEnabled = snapshot.flowsEnabled()) }
                }
            }
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
            // 「保存为任务」带入的会话摘录：消费一次即清，进程重建不重复自动发送。
            val hint = savedStateHandle.get<String>(TASK_HINT_ARG)?.takeIf { it.isNotBlank() }
            savedStateHandle[TASK_HINT_ARG] = null
            viewModelScope.launch {
                val s = author.open(taskId?.let(::TaskId))
                session = s
                launch { s.state.collect { st -> _uiState.update { it.copy(author = st) } } }
                hint?.let { text -> sendJob = launch { s.send(text) } }
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
                is TaskAuthorAction.SetModel -> _uiState.update { it.copy(modelProfileId = action.profileId) }
                TaskAuthorAction.Save -> save(s, navigator)
                TaskAuthorAction.StartVoice, TaskAuthorAction.StopVoice -> handleVoice(action)
                is TaskAuthorAction.VoiceFailed -> handleVoice(action)
            }
        }

        /** 语音输入：识别中的临时结果写进输入框（接在开始时的文字后面），确定结果并入输入框。 */
        private fun collectVoice() {
            viewModelScope.launch {
                speech.listening.collect { on -> _uiState.update { it.copy(listening = on) } }
            }
            viewModelScope.launch {
                speech.partial.collect { p ->
                    if (speech.listening.value && p.isNotEmpty()) {
                        _uiState.update { it.copy(input = joinVoiceText(voiceBase, p)) }
                    }
                }
            }
            viewModelScope.launch {
                speech.results.collect { r ->
                    voiceBase = joinVoiceText(voiceBase, r)
                    _uiState.update { it.copy(input = voiceBase) }
                }
            }
            viewModelScope.launch {
                speech.errors.collect { code -> _uiState.update { it.copy(voiceError = code) } }
            }
        }

        private fun handleVoice(action: TaskAuthorAction) {
            when (action) {
                TaskAuthorAction.StartVoice -> {
                    voiceBase = _uiState.value.input
                    _uiState.update { it.copy(voiceError = null) }
                    speech.start()
                }
                TaskAuthorAction.StopVoice -> speech.stop()
                is TaskAuthorAction.VoiceFailed -> _uiState.update { it.copy(voiceError = action.code) }
                else -> Unit
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
            sendJob = viewModelScope.launch { s.send(text, _uiState.value.modelProfileId) }
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
