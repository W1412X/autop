/**
 * 对话列表页 ViewModel（docs/tech/08 §4.1，docs/standards/coding-standards §8）。
 *
 * 读：[ThreadStore.listThreads] 分页、[ThreadStore.observeChanges]（外部新建/追加/改名/
 * 删除即时刷新，N184）、[AccessibilityStatus.connected]、[RunService.observeActive]、
 * [RunService.observeRunningThreads] / [RunService.observeOpenRuns]（会话行实时表情）；
 * 写：[ThreadStore.rename] / [ThreadStore.delete]、[RunService.cancel]（停止运行中的 Run）。
 */
package com.example.autop.feature.chat.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunState
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ThreadSummary
import com.example.autop.core.common.Capability
import com.example.autop.core.common.PlatformCapabilities
import com.example.autop.core.common.ThreadId
import com.example.autop.core.ui.component.ballface.BallFaceDirector
import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.core.ui.component.ballface.BallFaceScene
import com.example.autop.device.AccessibilityStatus
import com.example.autop.device.ScreenLease
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunRecord
import com.example.autop.task.RunService
import com.example.autop.task.RunStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 分页状态的内部积累。 */
private data class PageAccum(
    val items: List<ThreadSummary> = emptyList(),
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val failed: Boolean = false,
)

/** 实时运行输入：活跃卡片、运行集合与逐对话未收口 Run（行表情按它切换）。 */
private data class LiveRuns(
    val active: ActiveRunInfo?,
    val running: Set<ThreadId>,
    val open: Map<ThreadId, RunRecord>,
)

@OptIn(FlowPreview::class) // debounce 为 FlowPreview API（同 BrowseViewModel）
@HiltViewModel
public class ChatListViewModel
    @Inject
    constructor(
        private val threadStore: ThreadStore,
        private val runService: RunService,
        private val accessibility: AccessibilityStatus,
        private val capabilities: PlatformCapabilities,
        private val shellInput: ShellInput,
        private val screenLease: ScreenLease,
    ) : ViewModel() {
        private val page = MutableStateFlow(PageAccum())
        private val sheet = MutableStateFlow(ChatListSheet())

        public val uiState: StateFlow<ChatListUiState> =
            combine(
                accessibility.connected,
                combine(
                    runService.observeActive(),
                    runService.observeRunningThreads(),
                    runService.observeOpenRuns(),
                    screenLease.holder,
                ) { active, running, openRuns, holder ->
                    LiveRuns(
                        // 头部卡片只为占用屏幕的运行显示；后台运行不占用卡片
                        active = if (active?.run?.id?.value == holder) active else null,
                        running = running,
                        open = openRuns,
                    )
                },
                page,
                sheet,
                shellInput.state,
            ) { connected, live, accum, sheets, shell ->
                ChatListUiState(
                    accessibilityConnected = connected,
                    restrictedSettings = capabilities.has(Capability.RESTRICTED_SETTINGS),
                    shellConnected = shell is ShellState.Connected,
                    runningCount = live.running.size,
                    threads = accum.items.map { toRow(it, live) },
                    paging =
                        ChatPaging(
                            loading = accum.loading,
                            endReached = accum.endReached,
                            failed = accum.failed,
                        ),
                    active = live.active.toCard(accum.items),
                    sheet = sheets,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ChatListUiState(),
            )

        init {
            loadMore()
            // 外部变更即时刷新（N184）：悬浮球 / 任务触发 / 对话页新建对话、新消息、改名、
            // 删除都只动存储不重建本 VM（随 NavBackStackEntry 存活），不订阅就只能重启应用才能看到。
            viewModelScope.launch {
                threadStore.observeChanges().debounce(REFRESH_DEBOUNCE_MS).collect { refresh() }
            }
        }

        public fun onAction(action: ChatListAction) {
            when (action) {
                ChatListAction.LoadMore -> loadMore()
                is ChatListAction.ShowThreadMenu -> sheet.update { it.copy(menuThreadId = action.threadId) }
                // 只清菜单目标；用 copy 按字段名保留重命名/删除目标（按位置传参会让它们错位到别的字段）。
                ChatListAction.DismissMenu -> sheet.update { it.copy(menuThreadId = null) }
                is ChatListAction.ShowRename ->
                    sheet.update { ChatListSheet(renameThreadId = action.threadId) }
                is ChatListAction.Rename -> rename(action.threadId, action.title)
                ChatListAction.DismissRename -> sheet.update { it.copy(renameThreadId = null) }
                is ChatListAction.ShowDelete ->
                    sheet.update { ChatListSheet(deleteThreadId = action.threadId) }
                ChatListAction.ConfirmDelete -> delete(sheet.value.deleteThreadId)
                ChatListAction.DismissDelete -> sheet.update { it.copy(deleteThreadId = null) }
                ChatListAction.StopActive -> stopActive()
                // 导航类动作（NewChat / OpenThread / EnableAccessibility）由 NavGraph 的 Route 层拦截。
                ChatListAction.NewChat,
                is ChatListAction.OpenThread,
                ChatListAction.EnableAccessibility,
                -> Unit
            }
        }

        private fun loadMore() {
            val current = page.value
            if (current.loading || current.endReached) return
            page.update { it.copy(loading = true, failed = false) }
            viewModelScope.launch {
                val result = runCatching { threadStore.listChatThreads(current.items.size, PAGE_SIZE) }
                result.fold(
                    onSuccess = { next ->
                        page.update {
                            it.copy(
                                items = it.items + next,
                                loading = false,
                                endReached = next.size < PAGE_SIZE,
                            )
                        }
                    },
                    onFailure = {
                        page.update { it.copy(loading = false, failed = true) }
                    },
                )
            }
        }

        private fun rename(
            threadId: String,
            title: String,
        ) {
            sheet.update { ChatListSheet() }
            val trimmed = title.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch {
                runCatching { threadStore.rename(ThreadId(threadId), trimmed) }
                refresh()
            }
        }

        private fun delete(threadId: String?) {
            sheet.update { ChatListSheet() }
            if (threadId == null) return
            viewModelScope.launch {
                runCatching { threadStore.delete(ThreadId(threadId)) }
                refresh()
            }
        }

        private fun stopActive() {
            val runId =
                runService
                    .observeActive()
                    .value
                    ?.run
                    ?.id ?: return
            runService.cancel(runId)
        }

        /** 改名 / 删除 / 外部变更后按已加载条数整体重载（不重置分页进度；列表体量小，简单可靠）。 */
        private fun refresh() {
            val keep =
                page.value.items.size
                    .coerceAtLeast(PAGE_SIZE)
            page.update { it.copy(loading = true, failed = false) }
            viewModelScope.launch {
                runCatching { threadStore.listChatThreads(offset = 0, limit = keep) }.fold(
                    onSuccess = { items ->
                        page.update {
                            it.copy(items = items, loading = false, endReached = items.size < keep)
                        }
                    },
                    onFailure = {
                        page.update { it.copy(loading = false, failed = true) }
                    },
                )
            }
        }

        private companion object {
            const val PAGE_SIZE = 20

            /** 变更信号去抖：运行中的 Run 会密集 append，攒一攒再刷。 */
            const val REFRESH_DEBOUNCE_MS = 300L
        }
    }

private fun toRow(
    summary: ThreadSummary,
    live: LiveRuns,
): ChatThreadRow =
    ChatThreadRow(
        threadId = summary.threadId.value,
        title = summary.title,
        preview = summary.preview,
        status = summary.lastRunStatus,
        updatedAt = summary.updatedAt,
        faceMood = liveFaceOf(live.open[summary.threadId], running = summary.threadId in live.running),
    )

/**
 * 会话行前导表情：优先按该对话未收口 Run 的实时状态，其次退回运行集合。
 * ask_user（WAITING_USER）与暂停等中间态在 ThreadSummary 里看不到，必须走开放 Run。
 */
internal fun liveFaceOf(
    record: RunRecord?,
    running: Boolean,
): BallFaceMood? =
    when {
        record == null -> if (running) BallFaceMood.RUNNING else null
        record.pauseReason == PauseReason.POLICY_BLOCKED -> BallFaceMood.BLOCKED
        record.waitingQuestion != null -> BallFaceMood.WAITING
        record.status == RunStatus.PAUSED -> BallFaceMood.PAUSED
        record.status == RunStatus.WAITING_USER || record.status == RunStatus.WAITING_SCREEN -> BallFaceMood.WAITING
        record.status in RunStatus.OPEN -> BallFaceMood.RUNNING
        running -> BallFaceMood.RUNNING
        else -> null
    }

private fun ActiveRunInfo?.toCard(threads: List<ThreadSummary>): ActiveRunCardModel? {
    val info = this ?: return null
    return ActiveRunCardModel(
        runId = info.run.id.value,
        title = info.run.threadId.let { id -> threads.firstOrNull { it.threadId == id }?.title },
        currentStep = info.currentStep,
        scene = chatSceneOf(info.displayState),
        seed = BallFaceDirector.seedOfEvent(info.run.id.value, info.stepCount),
    )
}

/**
 * Run 执行阶段 → 表情场景。对话卡片不知道当前工具类别，执行工具时按思考池处理；
 * 悬浮球会按工具类别选池，两处语义同属一族。
 */
private fun chatSceneOf(state: RunState): BallFaceScene =
    when (state) {
        RunState.STARTING -> BallFaceScene.STARTING
        RunState.COMPACTING -> BallFaceScene.COMPACTING
        else -> BallFaceScene.SAMPLING
    }
