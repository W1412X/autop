/**
 * 对话页 ViewModel（docs/tech/08 §4.2，docs/standards/coding-standards §8）。
 *
 * 读：[ThreadStore.observeFrom]（历史，WP8.3 先回放后实时、不丢事件）+ [RunService.liveEvents]
 * （实时增量）→ [mergeTimeline]；[RunService.observeOpenRun]（本线程未收口 Run，WP8.5）
 * 与 [RunService.observeActive]（ask_user 问题）；写：[RunService.startChat] / [steer] /
 * [cancel] / [resume] / [answer]、[AgentSession.fork]（从这里分叉）、[ThreadStore.delete]。
 */
package com.example.autop.feature.chat.thread

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.agent.AgentEvent
import com.example.autop.agent.AgentSession
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.EventPayload
import com.example.autop.agent.RunFinished
import com.example.autop.agent.ThreadCreated
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolResultEvent
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.LogFiles
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.ThreadId
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.device.AccessibilityStatus
import com.example.autop.feature.chat.NEW_THREAD
import com.example.autop.llm.ModelProfileStore
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.ImageAttachment
import com.example.autop.task.RunRecord
import com.example.autop.task.RunService
import com.example.autop.task.RunStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    /** 会话级模型覆盖（null = 跟随默认）。 */
    val modelProfileId: String? = null,
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
        private val modelProfiles: ModelProfileStore,
        private val runService: RunService,
        private val agentSession: AgentSession,
        private val configProvider: ConfigProvider,
        private val accessibility: AccessibilityStatus,
        private val logFiles: LogFiles,
        private val imageReader: ChatImageReader,
    ) : ViewModel() {
        private val timeline = MutableStateFlow(TimelineInput())
        private val meta = MutableStateFlow(ChatMeta())
        private val threadTitle = MutableStateFlow<String?>(null)
        private val threadFlow = MutableStateFlow<String?>(null)
        private var threadId: String = NEW_THREAD
        private var bound = false

        /** 历史订阅（WP8.3）：切线程（fork/换对话）时 cancel 停掉旧订阅。 */
        private var collectJob: Job? = null

        /** 本线程当前未收口的 Run（WP8.5）：Stop/Resume/Answer/steer 都只作用于它。 */
        public val openRun: StateFlow<RunRecord?> =
            threadFlow
                .flatMapLatest { id -> if (id == null) flowOf(null) else runService.observeOpenRun(ThreadId(id)) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val navChannel = Channel<ChatNavEvent>(Channel.BUFFERED)

        /** 一次性导航事件（NavGraph 的 Route 层收集后转 [com.example.autop.feature.chat.ChatNavigator]）。 */
        public val navEvents: Flow<ChatNavEvent> = navChannel.receiveAsFlow()

        private val baseState: StateFlow<ChatThreadUiState> =
            combine(
                timeline,
                runService.observeActive(),
                meta,
                openRun,
                accessibility.connected,
            ) { input, active, metaState, open, connected ->
                buildState(input, active, open, metaState, connected)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ChatThreadUiState(),
            )

        /**
         * 标题以线程库为准（占位/LLM 精炼/列表改名都走 rename，事件流不带标题变更），
         * 事件流状态之上再叠一层。
         */
        private val modelOptions: StateFlow<List<OptionItem>> =
            modelProfiles.profiles
                .map { profiles -> profiles.map { OptionItem(id = it.id, label = it.displayName) } }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        public val uiState: StateFlow<ChatThreadUiState> =
            combine(baseState, threadTitle, modelOptions) { state, title, options ->
                state.copy(title = title ?: state.title, modelOptions = options)
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
                openRun
                    .map { it?.id }
                    .distinctUntilChanged()
                    .flatMapLatest { runId ->
                        if (runId == null) emptyFlow() else runService.liveEvents(runId)
                    }.collect { event ->
                        if (event is AgentEvent.SampleRetrying) {
                            // 重试前丢弃本轮已显示的增量（WP6.6）。
                            timeline.update {
                                it.copy(
                                    live =
                                        it.live.filterNot { e ->
                                            e is AgentEvent.TextDelta ||
                                                e is AgentEvent.ThinkingDelta ||
                                                e is AgentEvent.ToolCallStreaming
                                        },
                                )
                            }
                        } else if (event !is AgentEvent.Persisted) {
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
                is ChatThreadAction.SetModel,
                -> handleAttachments(action)
                ChatThreadAction.Send -> send()
                ChatThreadAction.Stop ->
                    openRun.value?.let { runService.cancel(it.id) }
                ChatThreadAction.Resume ->
                    openRun.value?.let { runService.resume(it.id, note = null) }
                is ChatThreadAction.Answer -> answer(action.text)
                is ChatThreadAction.ForkFrom -> forkFrom(action.seq)
                ChatThreadAction.ConfirmDelete -> deleteThread()
                is ChatThreadAction.OpenSourceThread ->
                    viewModelScope.launch { navChannel.send(ChatNavEvent.ReplaceThread(action.threadId)) }
                is ChatThreadAction.OpenLink ->
                    viewModelScope.launch { navChannel.send(ChatNavEvent.OpenLink(action.link)) }
                else -> handleSheet(action)
            }
        }

        private fun handleAttachments(action: ChatThreadAction) {
            when (action) {
                is ChatThreadAction.AddImages -> addImages(action.uris)
                is ChatThreadAction.RemoveImage ->
                    meta.update { it.copy(images = it.images.filterIndexed { index, _ -> index != action.index }) }
                is ChatThreadAction.SetModel -> setModel(action.profileId)
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
                ChatThreadAction.SaveAsTask -> {
                    meta.update { it.copy(sheet = it.sheet.copy(moreMenu = false)) }
                    val hint = taskHintOf(uiState.value.entries)
                    viewModelScope.launch { navChannel.send(ChatNavEvent.OpenTaskCreate(hint)) }
                }
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
            val open = openRun.value
            // 运行中只能补充文字指令（图片随新一轮对话发送）。
            if (text.isEmpty() && (open != null || images.isEmpty())) return
            val background = configProvider.current()[UiConfig.CHAT].backgroundOnSend
            if (open != null) {
                // steer 失败不清输入框（WP8.5）：成功后才清，失败提示 STEER_FAILED。
                viewModelScope.launch {
                    if (runService.steer(open.id, text) is Outcome.Ok) {
                        meta.update { it.copy(input = "", notice = null) }
                        if (background) navChannel.send(ChatNavEvent.Background)
                    } else {
                        meta.update { it.copy(notice = ThreadNotice.STEER_FAILED) }
                    }
                }
                return
            }
            meta.update { it.copy(input = "", images = emptyList()) }
            viewModelScope.launch {
                val fresh = threadId == NEW_THREAD
                val outcome =
                    runService.startChat(
                        threadId = if (fresh) null else ThreadId(threadId),
                        text = text,
                        images = images,
                        modelProfileId = meta.value.modelProfileId,
                    )
                outcome.getOrNull()?.let { record ->
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
            val runId = openRun.value?.id ?: return
            meta.update { it.copy(input = "") }
            viewModelScope.launch { runService.answer(runId, text) }
        }

        /** 切换会话级模型：已有会话立即持久化，新对话随首条消息持久化（ADR-0022）。 */
        private fun setModel(profileId: String?) {
            meta.update { it.copy(modelProfileId = profileId) }
            if (threadId != NEW_THREAD) {
                viewModelScope.launch { threadStore.setModelProfile(ThreadId(threadId), profileId) }
            }
        }

        private fun forkFrom(seq: Long) {
            meta.update { it.copy(sheet = it.sheet.copy(menuSeq = null)) }
            viewModelScope.launch {
                agentSession.fork(ThreadId(threadId), seq, title = null).getOrNull()?.let { forked ->
                    threadId = forked.value
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
            threadFlow.value = threadId
            collectJob?.cancel()
            collectJob =
                viewModelScope.launch {
                    threadTitle.value = threadStore.titleOf(id)
                    meta.update { it.copy(modelProfileId = threadStore.modelProfileOf(id)) }
                    timeline.value = TimelineInput()
                    // observeFrom 先回放已有事件再转实时（WP8.3）：新对话的用户输入事件
                    // 在入队时已落盘（WP8.1），backlog 一定包含它，不再丢。
                    threadStore.observeFrom(id, afterSeq = 0).collect { batch ->
                        timeline.update { input ->
                            val clearsLive =
                                batch.any {
                                    it.payload is AssistantMessage ||
                                        it.payload is ToolResultEvent ||
                                        it.payload is RunFinished
                                }
                            input.copy(
                                history = input.history + batch,
                                live = if (clearsLive) emptyList() else input.live,
                            )
                        }
                    }
                }
        }

        private fun buildState(
            input: TimelineInput,
            active: ActiveRunInfo?,
            open: RunRecord?,
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
                running = open != null,
                accessibilityConnected = connected,
                input = metaState.input,
                modelProfileId = metaState.modelProfileId,
                images = metaState.images,
                maxImages = chatConfig.attachMaxImages,
                inputMaxLines = chatConfig.inputMaxLines,
                waitingQuestion =
                    active
                        ?.takeIf { open != null && it.run.id == open.id && it.run.status == RunStatus.WAITING_USER }
                        ?.let { WaitingQuestion(it.run.waitingQuestion ?: "", it.run.waitingOptions) },
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
    }

/** 标题刷新去抖（事件连发时合并成一次库查询，同对话列表 N184 口径）。 */
private const val TITLE_REFRESH_DEBOUNCE_MS: Long = 300

/** 「保存为任务」带给任务创作页的输入：用户消息摘录。 */
private fun taskHintOf(entries: List<TimelineEntry>): String =
    entries
        .mapNotNull { (it.item as? TimelineItem.UserBubble)?.text }
        .joinToString("\n")
        .take(TASK_HINT_MAX_CHARS)

private const val TASK_HINT_MAX_CHARS: Int = 200
