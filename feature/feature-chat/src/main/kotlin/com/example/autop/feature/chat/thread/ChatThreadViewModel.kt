/**
 * 对话页 ViewModel（docs/tech/08 §4.2，docs/standards/coding-standards §8）。
 *
 * 读：[ThreadStore.events] / [ThreadStore.observe]（历史）+ [RunService.liveEvents]
 * （实时增量）→ [mergeTimeline]；[RunService.observeActive]（本线程活跃 Run、
 * ask_user 问题）；写：[RunService.startChat] / [steer] / [cancel] / [resume] /
 * [answer]、[AgentSession.fork]（从这里分叉）、[ThreadStore.delete]。
 */
package com.example.autop.feature.chat.thread

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.agent.AgentEvent
import com.example.autop.agent.AgentSession
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.EventPayload
import com.example.autop.agent.ThreadCreated
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolResultEvent
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.LogFiles
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import com.example.autop.device.AccessibilityStatus
import com.example.autop.feature.chat.NEW_THREAD
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.ImageAttachment
import com.example.autop.task.RunService
import com.example.autop.task.RunStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 归并输入：已持久化事件 + 实时增量。 */
private data class TimelineInput(
    val history: List<ThreadEvent> = emptyList(),
    val live: List<AgentEvent> = emptyList(),
)

/** 页面本地状态（输入、开关、弹层）。 */
private data class ChatMeta(
    val input: String = "",
    val images: List<ImageAttachment> = emptyList(),
    val attachScreen: Boolean = true,
    val sheet: ChatThreadSheet = ChatThreadSheet(),
    val notice: ThreadNotice? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class) // flatMapLatest 为实验 API，debounce 为 FlowPreview
@HiltViewModel
@Suppress("LongParameterList", "TooManyFunctions") // 对话页装配面与操作面（DI 注入/全部用户动作处理）
public class ChatThreadViewModel
    @Inject
    constructor(
        private val threadStore: ThreadStore,
        private val runService: RunService,
        private val agentSession: AgentSession,
        private val configProvider: ConfigProvider,
        private val accessibility: AccessibilityStatus,
        private val logFiles: LogFiles,
        private val imageReader: ChatImageReader,
    ) : ViewModel() {
        private val timeline = MutableStateFlow(TimelineInput())
        private val meta = MutableStateFlow(ChatMeta())
        private val sentRunId = MutableStateFlow<RunId?>(null)
        private val threadTitle = MutableStateFlow<String?>(null)
        private var threadId: String = NEW_THREAD
        private var bound = false

        private val navChannel = Channel<ChatNavEvent>(Channel.BUFFERED)

        /** 一次性导航事件（NavGraph 的 Route 层收集后转 [com.example.autop.feature.chat.ChatNavigator]）。 */
        public val navEvents: Flow<ChatNavEvent> = navChannel.receiveAsFlow()

        private val baseState: StateFlow<ChatThreadUiState> =
            combine(
                timeline,
                runService.observeActive(),
                meta,
                sentRunId,
                accessibility.connected,
            ) { input, active, metaState, sent, connected ->
                buildState(input, activeOf(active, sent), metaState, connected)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ChatThreadUiState(),
            )

        /**
         * 标题以线程库为准（占位/LLM 精炼/列表改名都走 rename，事件流不带标题变更），
         * 事件流状态之上再叠一层。
         */
        public val uiState: StateFlow<ChatThreadUiState> =
            combine(baseState, threadTitle) { state, title ->
                state.copy(title = title ?: state.title)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ChatThreadUiState(),
            )

        init {
            viewModelScope.launch {
                threadStore
                    .observeChanges()
                    .debounce(TITLE_REFRESH_DEBOUNCE_MS)
                    .collect {
                        if (threadId != NEW_THREAD) threadTitle.value = threadStore.titleOf(ThreadId(threadId))
                    }
            }
            viewModelScope.launch {
                combine(runService.observeActive(), sentRunId) { active, sent -> activeOf(active, sent)?.run?.id }
                    .distinctUntilChanged()
                    .flatMapLatest { runId ->
                        if (runId == null) emptyFlow() else runService.liveEvents(runId)
                    }.collect { event ->
                        if (event !is AgentEvent.Persisted) {
                            timeline.update { it.copy(live = it.live + event) }
                        }
                    }
            }
        }

        /** 由 NavGraph 在路由合成时绑定线程（"new" 表示未建线程的新对话）。 */
        public fun bindThread(threadId: String) {
            if (bound) return
            bound = true
            this.threadId = threadId
            if (threadId != NEW_THREAD) {
                startCollecting(threadId)
            }
        }

        public fun onAction(action: ChatThreadAction) {
            when (action) {
                is ChatThreadAction.SetInput -> meta.update { it.copy(input = action.text) }
                is ChatThreadAction.AddImages,
                is ChatThreadAction.RemoveImage,
                ChatThreadAction.ToggleAttachScreen,
                -> handleAttachments(action)
                ChatThreadAction.Send -> send()
                ChatThreadAction.Stop ->
                    runService.observeActive().value?.let { runService.cancel(it.run.id) }
                ChatThreadAction.Resume ->
                    runService.observeActive().value?.let { runService.resume(it.run.id, note = null) }
                is ChatThreadAction.Answer -> answer(action.text)
                is ChatThreadAction.ForkFrom -> forkFrom(action.seq)
                ChatThreadAction.ConfirmDelete -> deleteThread()
                is ChatThreadAction.OpenSourceThread ->
                    viewModelScope.launch { navChannel.send(ChatNavEvent.ReplaceThread(action.threadId)) }
                else -> handleSheet(action)
            }
        }

        private fun handleAttachments(action: ChatThreadAction) {
            when (action) {
                is ChatThreadAction.AddImages -> addImages(action.uris)
                is ChatThreadAction.RemoveImage ->
                    meta.update { it.copy(images = it.images.filterIndexed { index, _ -> index != action.index }) }
                ChatThreadAction.ToggleAttachScreen -> meta.update { it.copy(attachScreen = !it.attachScreen) }
                else -> Unit
            }
        }

        private fun handleSheet(action: ChatThreadAction) {
            when (action) {
                is ChatThreadAction.ShowEntryMenu ->
                    meta.update { it.copy(sheet = it.sheet.copy(menuSeq = action.seq)) }
                ChatThreadAction.CopyReply -> meta.update { it.copy(notice = ThreadNotice.COPIED) }
                ChatThreadAction.DismissEntryMenu ->
                    meta.update { it.copy(sheet = it.sheet.copy(menuSeq = null)) }
                is ChatThreadAction.ShowRaw ->
                    meta.update { it.copy(sheet = it.sheet.copy(rawSeq = action.seq, menuSeq = null)) }
                ChatThreadAction.DismissRaw ->
                    meta.update { it.copy(sheet = it.sheet.copy(rawSeq = null)) }
                ChatThreadAction.ShowMoreMenu ->
                    meta.update { it.copy(sheet = it.sheet.copy(moreMenu = true)) }
                ChatThreadAction.DismissMoreMenu ->
                    meta.update { it.copy(sheet = it.sheet.copy(moreMenu = false)) }
                ChatThreadAction.SaveAsTask ->
                    meta.update { it.copy(notice = ThreadNotice.SAVE_AS_TASK, sheet = it.sheet.copy(moreMenu = false)) }
                ChatThreadAction.ExportLog -> exportLog()
                ChatThreadAction.DeleteThread ->
                    meta.update { it.copy(sheet = it.sheet.copy(deleteConfirm = true, moreMenu = false)) }
                ChatThreadAction.DismissDelete ->
                    meta.update { it.copy(sheet = it.sheet.copy(deleteConfirm = false)) }
                ChatThreadAction.DismissNotice -> meta.update { it.copy(notice = null) }
                else -> Unit
            }
        }

        /** "导出日志"（N175）：组装事件 JSON lines + 应用日志，交 app 写文件并拉起分享。 */
        private fun exportLog() {
            meta.update { it.copy(sheet = it.sheet.copy(moreMenu = false), notice = ThreadNotice.EXPORT_LOG) }
            viewModelScope.launch {
                val text =
                    withContext(Dispatchers.IO) {
                        buildThreadExport(threadId, timeline.value.history, logFiles.readAll())
                    }
                navChannel.send(ChatNavEvent.ExportLog(text))
            }
        }

        /** 读取相册选中的图片（逐张压缩，不超过上限），读取失败的跳过并提示。 */
        private fun addImages(uris: List<Uri>) {
            val config = configProvider.current()[UiConfig.CHAT]
            val room = config.attachMaxImages - meta.value.images.size
            if (room <= 0) return
            viewModelScope.launch {
                val loaded = uris.take(room).map { imageReader.read(it, config).getOrNull() }
                meta.update { state ->
                    state.copy(
                        images = (state.images + loaded.filterNotNull()).take(config.attachMaxImages),
                        notice = if (loaded.any { it == null }) ThreadNotice.IMAGE_FAILED else state.notice,
                    )
                }
            }
        }

        private fun send() {
            val text = meta.value.input.trim()
            val images = meta.value.images
            val active = runService.observeActive().value
            // 运行中只能补充文字指令（图片随新一轮对话发送）。
            if (text.isEmpty() && (active != null || images.isEmpty())) return
            val attach = meta.value.attachScreen
            meta.update { it.copy(input = "", images = if (active == null) emptyList() else it.images) }
            val background = configProvider.current()[UiConfig.CHAT].backgroundOnSend
            if (active != null) {
                runService.steer(active.run.id, text)
                if (background) viewModelScope.launch { navChannel.send(ChatNavEvent.Background) }
                return
            }
            viewModelScope.launch {
                val fresh = threadId == NEW_THREAD
                val outcome = runService.startChat(if (fresh) null else ThreadId(threadId), text, attach, images)
                outcome.getOrNull()?.let { record ->
                    sentRunId.value = record.id
                    if (fresh) {
                        threadId = record.threadId.value
                        startCollecting(record.threadId.value)
                        navChannel.send(ChatNavEvent.ReplaceThread(record.threadId.value))
                    }
                    if (background) navChannel.send(ChatNavEvent.Background)
                }
            }
        }

        private fun answer(text: String) {
            val runId =
                runService
                    .observeActive()
                    .value
                    ?.run
                    ?.id ?: return
            meta.update { it.copy(input = "") }
            viewModelScope.launch { runService.answer(runId, text) }
        }

        private fun forkFrom(seq: Long) {
            meta.update { it.copy(sheet = it.sheet.copy(menuSeq = null)) }
            viewModelScope.launch {
                agentSession.fork(ThreadId(threadId), seq, title = null).getOrNull()?.let { forked ->
                    threadId = forked.value
                    sentRunId.value = null
                    timeline.value = TimelineInput()
                    startCollecting(forked.value)
                    navChannel.send(ChatNavEvent.ReplaceThread(forked.value))
                }
            }
        }

        private fun deleteThread() {
            meta.update { it.copy(sheet = ChatThreadSheet()) }
            viewModelScope.launch {
                if (threadId != NEW_THREAD) {
                    runCatching { threadStore.delete(ThreadId(threadId)) }
                }
                navChannel.send(ChatNavEvent.NavigateBack)
            }
        }

        private fun startCollecting(threadId: String) {
            val id = ThreadId(threadId)
            viewModelScope.launch {
                threadTitle.value = threadStore.titleOf(id)
                timeline.update { it.copy(history = threadStore.events(id)) }
                threadStore.observe(id).collect { event ->
                    timeline.update { input ->
                        val live =
                            if (event.payload is AssistantMessage || event.payload is ToolResultEvent) {
                                emptyList()
                            } else {
                                input.live
                            }
                        input.copy(history = input.history + event, live = live)
                    }
                }
            }
        }

        private fun buildState(
            input: TimelineInput,
            active: ActiveRunInfo?,
            metaState: ChatMeta,
            connected: Boolean,
        ): ChatThreadUiState {
            val chatConfig = configProvider.current()[UiConfig.CHAT]
            val rawSeq = metaState.sheet.rawSeq
            val rawEvent = rawSeq?.let { seq -> input.history.firstOrNull { it.seq == seq } }
            return ChatThreadUiState(
                threadId = threadId,
                title = input.history.firstNotNullOfOrNull { (it.payload as? ThreadCreated)?.title },
                entries = mergeTimeline(input.history, input.live, chatConfig.stepGroupThreshold),
                running = active != null,
                accessibilityConnected = connected,
                input = metaState.input,
                images = metaState.images,
                maxImages = chatConfig.attachMaxImages,
                attachScreen = metaState.attachScreen,
                inputMaxLines = chatConfig.inputMaxLines,
                waitingQuestion =
                    active?.takeIf { it.run.status == RunStatus.WAITING_USER }?.let {
                        WaitingQuestion(it.run.waitingQuestion ?: "", it.run.waitingOptions)
                    },
                notice = metaState.notice,
                sheet = metaState.sheet,
                rawJson =
                    rawEvent?.let {
                        runCatching {
                            AutopJson.default.encodeToString(EventPayload.serializer(), it.payload)
                        }.getOrNull()
                    },
            )
        }

        private fun activeOf(
            active: ActiveRunInfo?,
            sent: RunId?,
        ): ActiveRunInfo? = active?.takeIf { it.run.threadId.value == threadId || it.run.id == sent }
    }

/** 标题刷新去抖（事件连发时合并成一次库查询，同对话列表 N184 口径）。 */
private const val TITLE_REFRESH_DEBOUNCE_MS: Long = 300
