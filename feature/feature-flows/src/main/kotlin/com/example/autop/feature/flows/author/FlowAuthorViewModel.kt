/**
 * AI 创建流程 ViewModel：把创作会话（flow-api 的 [FlowAuthorSession]）接到界面。
 */
package com.example.autop.feature.flows.author

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.ImageAttachment
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import com.example.autop.core.thread.ui.ChatImageReader
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.joinVoiceText
import com.example.autop.device.SpeechInput
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
    val images: List<ImageAttachment> = emptyList(),
    val imageError: Boolean = false,
    val leaveConfirm: Boolean = false,
    val saveError: String? = null,
    /** 系统语音识别是否可用（不可用时不显示麦克风）。 */
    val voiceAvailable: Boolean = false,
    /** 正在语音输入。 */
    val listening: Boolean = false,
    /** 语音输入出错的错误码（提示用，见界面）。 */
    val voiceError: String? = null,
)

/** 创作页意图。 */
public sealed interface FlowAuthorAction {
    public data object Back : FlowAuthorAction

    public data class SetInput(
        val value: String,
    ) : FlowAuthorAction

    public data object Send : FlowAuthorAction

    public data class AddImages(
        val uris: List<Uri>,
    ) : FlowAuthorAction

    public data class RemoveImage(
        val index: Int,
    ) : FlowAuthorAction

    public data object ImageFailed : FlowAuthorAction

    public data object LeaveKeepRunning : FlowAuthorAction

    public data object LeaveCancelRun : FlowAuthorAction

    public data object DismissLeave : FlowAuthorAction

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

    /** 开始语音输入（麦克风权限由界面先行取得）。 */
    public data object StartVoice : FlowAuthorAction

    /** 结束语音输入。 */
    public data object StopVoice : FlowAuthorAction

    /** 语音输入失败（如权限被拒），以错误码提示。 */
    public data class VoiceFailed(
        val code: String,
    ) : FlowAuthorAction
}

/** 创作页 ViewModel。 */
@HiltViewModel
public class FlowAuthorViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val author: FlowAuthor,
        private val modelProfiles: ModelProfileStore,
        private val speech: SpeechInput,
        private val imageReader: ChatImageReader,
        private val configProvider: ConfigProvider,
    ) : ViewModel() {
        private val draftId: String? = savedStateHandle.get<String>(AUTHOR_DRAFT_ARG)?.takeIf { it != NO_ARG }
        private val editId: String? = savedStateHandle.get<String>(AUTHOR_EDIT_ARG)?.takeIf { it != NO_ARG }
        private val _uiState = MutableStateFlow(FlowAuthorUiState(voiceAvailable = speech.available()))
        public val uiState: StateFlow<FlowAuthorUiState> = _uiState
        private var session: FlowAuthorSession? = null
        private var sendJob: Job? = null

        /** 开始语音输入时输入框已有的文字；识别结果接在它后面。 */
        private var voiceBase: String = ""

        init {
            collectVoice()
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
                FlowAuthorAction.Back,
                FlowAuthorAction.LeaveKeepRunning,
                FlowAuthorAction.LeaveCancelRun,
                FlowAuthorAction.DismissLeave,
                ->
                    handleLeave(
                        s,
                        action,
                        navigator,
                    )
                is FlowAuthorAction.SetInput -> _uiState.update { it.copy(input = action.value) }
                FlowAuthorAction.Send -> send(s)
                is FlowAuthorAction.AddImages, is FlowAuthorAction.RemoveImage, FlowAuthorAction.ImageFailed ->
                    handleImages(
                        action,
                    )
                is FlowAuthorAction.Answer -> s?.answer(action.text)
                is FlowAuthorAction.Demonstration -> s?.confirmDemonstration(action.allowed)
                FlowAuthorAction.Stop -> {
                    s?.cancel()
                    sendJob?.cancel()
                }
                FlowAuthorAction.Save -> save(s, navigator)
                is FlowAuthorAction.SetModel -> _uiState.update { it.copy(modelProfileId = action.profileId) }
                FlowAuthorAction.OpenAdvanced -> save(s, navigator, openEditor = true)
                FlowAuthorAction.StartVoice, FlowAuthorAction.StopVoice -> handleVoice(action)
                is FlowAuthorAction.VoiceFailed -> handleVoice(action)
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

        private fun handleVoice(action: FlowAuthorAction) {
            when (action) {
                FlowAuthorAction.StartVoice -> {
                    voiceBase = _uiState.value.input
                    _uiState.update { it.copy(voiceError = null) }
                    speech.start()
                }
                FlowAuthorAction.StopVoice -> speech.stop()
                is FlowAuthorAction.VoiceFailed -> _uiState.update { it.copy(voiceError = action.code) }
                else -> Unit
            }
        }

        private fun handleImages(action: FlowAuthorAction) {
            when (action) {
                is FlowAuthorAction.AddImages -> addImages(action.uris)
                is FlowAuthorAction.RemoveImage ->
                    _uiState.update { st ->
                        st.copy(
                            images =
                                st.images.filterIndexed { i, _ ->
                                    i !=
                                        action.index
                                },
                        )
                    }
                FlowAuthorAction.ImageFailed -> _uiState.update { it.copy(imageError = true) }
                else -> Unit
            }
        }

        /** 返回：运行中先弹出确认（后台运行 / 取消运行 / 留在本页），空闲时直接返回。 */
        private fun handleLeave(
            s: FlowAuthorSession?,
            action: FlowAuthorAction,
            navigator: FlowsNavigator,
        ) {
            when (action) {
                FlowAuthorAction.Back ->
                    if (_uiState.value.author.busy) {
                        _uiState.update { it.copy(leaveConfirm = true) }
                    } else {
                        navigator.navigateBack()
                    }
                FlowAuthorAction.LeaveKeepRunning -> {
                    _uiState.update { it.copy(leaveConfirm = false) }
                    navigator.navigateBack()
                }
                FlowAuthorAction.LeaveCancelRun -> {
                    s?.cancel()
                    s?.close()
                    navigator.navigateBack()
                }
                else -> _uiState.update { it.copy(leaveConfirm = false) }
            }
        }

        /** 读取选中的图片（限额内），失败的计入 imageError。 */
        private fun addImages(uris: List<Uri>) {
            val config = configProvider.current()[UiConfig.CHAT]
            val room = config.attachMaxImages - _uiState.value.images.size
            if (room <= 0 || uris.isEmpty()) return
            viewModelScope.launch {
                val loaded = uris.take(room).map { imageReader.read(it, config).getOrNull() }
                _uiState.update { state ->
                    state.copy(
                        images = (state.images + loaded.filterNotNull()).take(config.attachMaxImages),
                        imageError = loaded.any { it == null },
                    )
                }
            }
        }

        private fun send(s: FlowAuthorSession?) {
            val text = _uiState.value.input.trim()
            val images = _uiState.value.images
            if (s == null || (text.isEmpty() && images.isEmpty())) return
            // 待回答的问题只接受文字，图片留在输入区
            if (_uiState.value.author.pendingQuestion != null) {
                if (text.isEmpty()) return
                s.answer(text)
                _uiState.update { it.copy(input = "") }
                return
            }
            if (_uiState.value.author.busy) return
            _uiState.update { it.copy(input = "", images = emptyList(), imageError = false) }
            sendJob = viewModelScope.launch { s.send(text, images, _uiState.value.modelProfileId) }
        }

        private fun save(
            s: FlowAuthorSession?,
            navigator: FlowsNavigator,
            openEditor: Boolean = false,
        ) {
            if (s == null) return
            viewModelScope.launch {
                when (val saved = s.save()) {
                    is Outcome.Ok -> {
                        s.close()
                        if (openEditor) {
                            navigator.openEditor(
                                saved.value.id.value,
                            )
                        } else {
                            navigator.openFlow(saved.value.id.value)
                        }
                    }
                    is Outcome.Err -> _uiState.update { it.copy(saveError = saved.error.message) }
                }
            }
        }

        override fun onCleared() {
            sendJob?.cancel()
            // 运行中的一轮留在会话里（后台运行），空闲时才关闭会话
            if (session?.state?.value?.busy != true) session?.close()
            super.onCleared()
        }
    }
