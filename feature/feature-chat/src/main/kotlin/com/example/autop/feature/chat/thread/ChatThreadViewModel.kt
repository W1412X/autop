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
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.nextAwaitingModel
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.ImageAttachment
import com.example.autop.core.common.LogFiles
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunMode
import com.example.autop.core.common.ThreadId
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.FeaturesConfig
import com.example.autop.core.config.UiConfig
import com.example.autop.core.thread.ui.ChatImageReader
import com.example.autop.core.thread.ui.TimelineEntry
import com.example.autop.core.thread.ui.TimelineItem
import com.example.autop.core.thread.ui.mergeTimeline
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.joinVoiceText
import com.example.autop.device.AccessibilityStatus
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import com.example.autop.device.SpeechInput
import com.example.autop.feature.chat.NEW_THREAD
import com.example.autop.llm.Block
import com.example.autop.llm.ModelProfileStore
import com.example.autop.task.ActiveRunInfo
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
import kotlinx.coroutines.flow.flowOn
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
    /** 刚发出、尚未在事件流中出现的用户消息文本（乐观显示，入库后清除）。 */
    val outgoing: String? = null,
    /** 当前模型请求是否仍在等待首个输出（[nextAwaitingModel] 折叠得到，随实时事件更新）。 */
    val awaitingModel: Boolean = false,
)

/** 归并结果（只随时间线变化重算）：界面条目、线程标题、历史（取原始事件用）。 */
private data class MergedTimeline(
    val history: List<ThreadEvent> = emptyList(),
    val title: String? = null,
    val entries: List<TimelineEntry> = emptyList(),
    val awaitingModel: Boolean = false,
)

/** 发出消息后等待第一条输出的运行状态（这些状态下才显示等待占位）。 */
private val AWAITING_REPLY_STATUSES: Set<RunStatus> =
    setOf(RunStatus.QUEUED, RunStatus.WAITING_SCREEN, RunStatus.RUNNING)

/** 事件是否为刚发出的那条用户消息（输入类，文本与待显示的一致）。 */
private fun ThreadEvent.isEchoOf(outgoing: String?): Boolean {
    if (outgoing == null) return false
    val message = payload as? UserMessage ?: return false
    if (message.kind != UserMessageKind.INPUT) return false
    val text = message.blocks.filterIsInstance<Block.Text>().joinToString(separator = "") { it.text }
    return text.trim() == outgoing
}

/** 把乐观显示的用户消息接在归并结果末尾（入库前只存在于界面，无 seq、不可分叉）。 */
private fun withOutgoing(
    entries: List<TimelineEntry>,
    outgoing: String?,
): List<TimelineEntry> =
    if (outgoing == null) {
        entries
    } else {
        val bubble = TimelineItem.UserBubble(text = outgoing, isSteer = false)
        entries + TimelineEntry(item = bubble, seq = null, forkable = false)
    }

/**
 * 等待模型首个输出时显示占位：当前模型请求仍无输出（[awaitingModel]，覆盖每一次模型请求），
 * 或运行刚开始、末尾仍是用户消息（启动阶段，事件尚未到达时的兜底）。
 */
private fun awaitingReplyOf(
    entries: List<TimelineEntry>,
    open: RunRecord?,
    awaitingModel: Boolean,
): Boolean {
    if (open == null) return false
    if (awaitingModel) return true
    return open.status in AWAITING_REPLY_STATUSES && entries.lastOrNull()?.item is TimelineItem.UserBubble
}

/** 页面本地状态（输入、开关、弹层）。 */
private data class ChatMeta(
    val input: String = "",
    val images: List<ImageAttachment> = emptyList(),
    /** 会话级模型覆盖（null = 跟随默认）。 */
    val modelProfileId: String? = null,
    /** 运行模式（ADR-0040）：下一次发送时生效；随线程持久化在 threads.db（ADR-0043），页面重建后沿用。 */
    val runMode: RunMode = RunMode.STANDARD,
    /** 完全模式无法切换时的阻断原因（非 null 时弹窗）。 */
    val shellBlock: ShellBlock? = null,
    val sheet: ChatThreadSheet = ChatThreadSheet(),
    val notice: ThreadNotice? = null,
    val voiceError: String? = null,
)

/** 模型选项与全局默认（输入区芯片展示默认模型名 + "默认"标记用）。 */
private data class ModelChoices(
    val options: List<OptionItem> = emptyList(),
    val defaultId: String? = null,
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
        private val speech: SpeechInput,
        private val shellInput: ShellInput,
        private val dispatchers: DispatcherProvider,
    ) : ViewModel() {
        /** 系统语音识别是否可用（设备没有识别服务时不显示麦克风）。 */
        private val voiceAvailable: Boolean = speech.available()

        /** 开始语音输入时输入框已有的文字；识别结果接在它后面。 */
        private var voiceBase = ""

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

        /**
         * 归并结果只随时间线（历史与实时增量）变化重算。输入框逐字输入、弹层开关等页面状态变化
         * 不会触发整条时间线的重新归并（主线程逐字输入时的卡顿来源之一）。
         */
        private val merged: StateFlow<MergedTimeline> =
            timeline
                // 归并在后台线程进行，不占主线程。StateFlow 只向收集方交付最新值，归并忙碌期间
                // 到达的中间状态会被合并，流式输出时不会堆积过期的全量归并。
                .map { input ->
                    val chatConfig = configProvider.current()[UiConfig.CHAT]
                    MergedTimeline(
                        history = input.history,
                        title = input.history.firstNotNullOfOrNull { (it.payload as? ThreadCreated)?.title },
                        entries =
                            withOutgoing(
                                mergeTimeline(input.history, input.live, chatConfig.stepGroupThreshold),
                                input.outgoing,
                            ),
                        awaitingModel = input.awaitingModel,
                    )
                }.flowOn(dispatchers.default)
                .stateIn(viewModelScope, SharingStarted.Eagerly, MergedTimeline())

        private val baseState: StateFlow<ChatThreadUiState> =
            combine(
                merged,
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

        /** 模型选项 + 全局默认 id（默认被删/未设置时按 null 处理）。 */
        private val modelChoices: StateFlow<ModelChoices> =
            combine(modelProfiles.profiles, modelProfiles.defaultProfileId) { profiles, defaultId ->
                ModelChoices(
                    options = profiles.map { OptionItem(id = it.id, label = it.displayName) },
                    defaultId = defaultId?.takeIf { id -> profiles.any { profile -> profile.id == id } },
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, ModelChoices())

        /**
         * 标题以线程库为准（占位/LLM 精炼/列表改名都走 rename，事件流不带标题变更），
         * 事件流状态之上再叠一层。
         */
        public val uiState: StateFlow<ChatThreadUiState> =
            combine(
                baseState,
                threadTitle,
                modelChoices,
                speech.listening,
                speech.level,
            ) { state, title, choices, listening, level ->
                state.copy(
                    title = title ?: state.title,
                    modelOptions = choices.options,
                    defaultModelId = choices.defaultId,
                    voiceAvailable = voiceAvailable,
                    listening = listening,
                    voiceLevel = level,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ChatThreadUiState(),
            )

        init {
            collectVoice()
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
                        timeline.update { input ->
                            val awaiting = nextAwaitingModel(input.awaitingModel, event)
                            when (event) {
                                // 重试前丢弃本轮已显示的增量（WP6.6）。
                                is AgentEvent.SampleRetrying ->
                                    input.copy(
                                        awaitingModel = awaiting,
                                        live =
                                            input.live.filterNot { e ->
                                                e is AgentEvent.TextDelta ||
                                                    e is AgentEvent.ThinkingDelta ||
                                                    e is AgentEvent.ToolCallStreaming
                                            },
                                    )
                                is AgentEvent.Persisted -> input.copy(awaitingModel = awaiting)
                                else -> input.copy(awaitingModel = awaiting, live = input.live + event)
                            }
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
                else -> {
                    updateChatOption(action)
                    handleVoice(action)
                    handleSheet(action)
                }
            }
        }

        /**
         * 运行模式（ADR-0040）：只改页面内存状态，下一次发送时生效。切到完全模式前检查 Shell 执行总闸与无线注入通道，不满足时只记录阻断原因。
         */
        private fun updateChatOption(action: ChatThreadAction) {
            when (action) {
                is ChatThreadAction.SetRunMode -> setRunMode(action.mode)
                ChatThreadAction.DismissShellBlock -> meta.update { it.copy(shellBlock = null) }
                else -> Unit
            }
        }

        private fun setRunMode(mode: RunMode) {
            val block = if (mode == RunMode.FULL) shellBlockNow() else null
            if (block != null) {
                meta.update { it.copy(shellBlock = block) }
                return
            }
            meta.update { it.copy(runMode = mode, shellBlock = null) }
            if (threadId != NEW_THREAD) {
                viewModelScope.launch { threadStore.setRunMode(ThreadId(threadId), mode) }
            }
        }

        /** 完全模式的前置条件：总闸开启，且无线注入已连接。 */
        private fun shellBlockNow(): ShellBlock? =
            when {
                !configProvider.current()[FeaturesConfig.FEATURES].shellExec -> ShellBlock.SWITCH_OFF
                shellInput.state.value != ShellState.Connected -> ShellBlock.CHANNEL_DISCONNECTED
                else -> null
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
            // 乐观显示：不等入库与运行创建，发出的消息立即出现在时间线末尾。
            timeline.update { it.copy(outgoing = text.takeIf { t -> t.isNotBlank() }) }
            viewModelScope.launch {
                val fresh = threadId == NEW_THREAD
                val outcome =
                    runService.startChat(
                        threadId = if (fresh) null else ThreadId(threadId),
                        text = text,
                        images = images,
                        modelProfileId = meta.value.modelProfileId,
                        runMode = meta.value.runMode,
                    )
                if (outcome is Outcome.Err) {
                    // 发送失败：撤销乐观消息，把原文和图片放回输入框（不丢用户内容）。
                    timeline.update { it.copy(outgoing = null) }
                    meta.update {
                        it.copy(
                            input = it.input.ifEmpty { text },
                            images = it.images.ifEmpty { images },
                            notice = ThreadNotice.SEND_FAILED,
                        )
                    }
                }
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

        /** 语音输入：识别中的临时结果实时写进输入框（接在开始时的文字后面）；确定结果并入输入框。 */
        private fun collectVoice() {
            collectVoiceErrors()
            viewModelScope.launch {
                speech.partial.collect { partial ->
                    if (speech.listening.value && partial.isNotEmpty()) {
                        meta.update { it.copy(input = joinVoice(voiceBase, partial)) }
                    }
                }
            }
            viewModelScope.launch {
                speech.results.collect { result ->
                    val text = joinVoice(voiceBase, result)
                    voiceBase = text
                    meta.update { it.copy(input = text) }
                }
            }
        }

        /** 语音识别出错：按错误码给出提示（错误码见 SystemSpeechInput）。 */
        private fun collectVoiceErrors() {
            viewModelScope.launch {
                speech.errors.collect { code ->
                    meta.update { it.copy(voiceError = code) }
                }
            }
        }

        private fun joinVoice(
            base: String,
            spoken: String,
        ): String = joinVoiceText(base, spoken)

        private fun handleVoice(action: ChatThreadAction) {
            when (action) {
                ChatThreadAction.StartVoice -> {
                    voiceBase = meta.value.input
                    meta.update { it.copy(voiceError = null) }
                    speech.start()
                }
                ChatThreadAction.StopVoice -> speech.stop()
                is ChatThreadAction.VoiceFailed -> meta.update { it.copy(voiceError = action.code) }
                is ChatThreadAction.ShowNotice -> meta.update { it.copy(notice = action.notice) }
                else -> Unit
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
                    // 分叉线程沿用当前模式，并持久化（ADR-0043）。
                    threadStore.setRunMode(forked, meta.value.runMode)
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
                    meta.update {
                        it.copy(modelProfileId = threadStore.modelProfileOf(id), runMode = threadStore.runModeOf(id))
                    }
                    timeline.update { TimelineInput(outgoing = it.outgoing) }
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
                                outgoing = if (batch.any { it.isEchoOf(input.outgoing) }) null else input.outgoing,
                            )
                        }
                    }
                }
        }

        private fun buildState(
            input: MergedTimeline,
            active: ActiveRunInfo?,
            open: RunRecord?,
            metaState: ChatMeta,
            connected: Boolean,
        ): ChatThreadUiState {
            val chatConfig = configProvider.current()[UiConfig.CHAT]
            val rawSeq = metaState.sheet.rawSeq
            val rawEvent = rawSeq?.let { seq -> input.history.firstOrNull { it.seq == seq } }
            val entries = input.entries
            return ChatThreadUiState(
                threadId = threadId,
                title = input.title,
                entries = entries,
                running = open != null,
                awaitingReply = awaitingReplyOf(entries, open, input.awaitingModel),
                accessibilityConnected = connected,
                input = metaState.input,
                modelProfileId = metaState.modelProfileId,
                runMode = metaState.runMode,
                shellBlock = metaState.shellBlock,
                voiceError = metaState.voiceError,
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
