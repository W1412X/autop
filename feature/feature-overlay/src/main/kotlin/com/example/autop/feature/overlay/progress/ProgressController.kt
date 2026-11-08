/**
 * 进度悬浮窗装配（docs/tech/08 §5.3，N182）：窗口注册 / 显示 / 收起、消息行供给，
 * 并在 ask_user（waitingQuestion 出现）与策略拦截（POLICY_BLOCKED）时**自动弹出**——
 * 不在对话页时 ask_user 没有任何 UI 的修复（N182）：提问卡随进度窗在任何页面可见。
 * 窗口 `register` 由 app 组装层调用（FEATURE 不依赖 IMPL，docs/tech/01 §4）。
 */
package com.example.autop.feature.overlay.progress

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.autop.agent.AgentEvent
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.PauseReason
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolResultEvent
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.ui.overlay.OverlayLifecycleOwner
import com.example.autop.device.OverlayHost
import com.example.autop.feature.overlay.OverlayWindowIds
import com.example.autop.feature.overlay.ball.BallStateHolder
import com.example.autop.feature.overlay.composeOverlayView
import com.example.autop.feature.overlay.panel.PanelRootView
import com.example.autop.feature.overlay.panel.PanelRunActions
import com.example.autop.flow.FlowProgressFeed
import com.example.autop.task.RunService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 消息行缓存上限（更早的行被裁掉，窗口只看当前 Run 的进度）。 */
private const val MAX_EVENTS: Int = 300

/** 消息行输入（历史事件 + 实时增量，N186；与对话页 TimelineInput 同款）。 */
private data class RowsInput(
    val history: List<ThreadEvent> = emptyList(),
    val live: List<AgentEvent> = emptyList(),
)

/** 进度悬浮窗的注册、显示与自动弹出（进程级）。 */
@Singleton
@Suppress("LongParameterList") // 装配类：端口 + 动作 + 数据源，不可再减（questions.md N114 口径）
public class ProgressController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val host: OverlayHost,
        private val holder: BallStateHolder,
        private val runService: RunService,
        private val threadStore: ThreadStore,
        private val actions: PanelRunActions,
        private val flowFeed: FlowProgressFeed,
        private val logger: Logger,
        dispatchers: DispatcherProvider,
    ) {
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.main + logger.exceptionHandler("ProgressController"))

        private val visibleState = MutableStateFlow(false)

        /** 进度窗是否可见。 */
        public val visible: StateFlow<Boolean> = visibleState.asStateFlow()

        private val rowsInput = MutableStateFlow(RowsInput())

        /** 消息行（历史 + 流式增量重算，N186）。 */
        private val rowsState: StateFlow<List<ProgressRow>> =
            rowsInput
                .map { progressRowsOf(it.history, it.live) }
                .stateIn(scope, SharingStarted.Eagerly, emptyList())

        /** 最近一次活跃 Run 的线程（N185：Run 结束后「打开对话」仍可用）。 */
        private val lastThreadState = MutableStateFlow<ThreadId?>(null)

        init {
            scope.launch { autoShow() }
            scope.launch { collectRows() }
        }

        /**
         * 自动弹出（N182）：ask_user 等待回答或策略拦截需要决策时弹出进度窗——
         * 只在「需要用户输入/决策」的迁移沿弹出，暂停等用户主动行为不打扰。
         */
        private suspend fun autoShow() {
            var lastQuestion: String? = null
            var lastBlocked = false
            runService.observeActive().collect { info ->
                val question = info?.run?.waitingQuestion
                val blocked = info?.run?.pauseReason == PauseReason.POLICY_BLOCKED
                if (question != null && lastQuestion == null) show()
                if (blocked && !lastBlocked) show()
                lastQuestion = question
                lastBlocked = blocked
            }
        }

        /**
         * 消息行供给：按活跃 Run 的线程跟取历史 + 实时增量；Run 收口后保留最后一轮（结束卡仍可见）。
         * 流式增量与对话页同款（N186）：文本/思考实时出行，“持久化到达即作废”由 [collectHistory] 清空。
         */
        private suspend fun collectRows() {
            runService
                .observeActive()
                .map { it?.run?.let { run -> run.threadId to run.id } }
                .distinctUntilChanged()
                .collectLatest { ids ->
                    if (ids != null) {
                        val (threadId, runId) = ids
                        lastThreadState.value = threadId
                        rowsInput.value = RowsInput(history = threadStore.eventsSinceLatestBaseline(threadId))
                        coroutineScope {
                            launch { collectLive(runId) }
                            collectHistory(threadId)
                        }
                    }
                }
        }

        /** 实时增量收集（N186，同对话页）：非 Persisted 事件进流式缓存。 */
        private suspend fun collectLive(runId: RunId) {
            runService.liveEvents(runId).collect { event ->
                if (event !is AgentEvent.Persisted) {
                    rowsInput.update { it.copy(live = it.live + event) }
                }
            }
        }

        /** 落盘事件追加；助手消息/工具结果落盘即作废流式缓存（同对话页，N186）。 */
        private suspend fun collectHistory(threadId: ThreadId) {
            threadStore.observe(threadId).collect { event ->
                rowsInput.update { input ->
                    val live =
                        if (event.payload is AssistantMessage || event.payload is ToolResultEvent) {
                            emptyList()
                        } else {
                            input.live
                        }
                    input.copy(history = (input.history + event).takeLast(MAX_EVENTS), live = live)
                }
            }
        }

        /** 进度窗内容工厂（app 组装层注册到 `OverlayWindowManager`）。 */
        public fun progressFactory(): (Context, OverlayLifecycleOwner) -> View =
            { windowContext, owner ->
                PanelRootView(
                    windowContext,
                    onOutsideTouch = {},
                    onBack = ::dismiss,
                ).apply {
                    addView(composeOverlayView(windowContext, owner) { ProgressContent() })
                }
            }

        /** 展开进度悬浮窗（已显示时不重复 show）。 */
        public fun show() {
            if (visibleState.value) return
            visibleState.value = true
            host.show(OverlayWindowIds.PROGRESS, progressSpec(context, holder.position.value))
        }

        /** 收起进度悬浮窗。 */
        public fun dismiss() {
            if (!visibleState.value) return
            visibleState.value = false
            host.hide(OverlayWindowIds.PROGRESS)
        }

        /** 窗口内容：活跃 Run + 消息行接 [ProgressScreen]。 */
        @Composable
        private fun ProgressContent() {
            val active by runService.observeActive().collectAsState()
            val rows by rowsState.collectAsState()
            val callbacks =
                remember(this) {
                    ProgressCallbacks(
                        onAnswer = actions::answer,
                        onPauseResume = actions::pauseResume,
                        onStop = actions::stop,
                        onStopFlow = {
                            // 只停流程、对话继续（P1-3）；停的是根 Run 名下登记的流程句柄。
                            val active = runService.observeActive()
                            val rootRunId = active.value?.run?.id
                            if (rootRunId != null) flowFeed.cancel(rootRunId)
                        },
                        onOpenThread = { actions.openThread(lastThreadState.value) },
                        onSteer = actions::steer,
                        onContinueBlocked = actions::continueBlocked,
                        onFinishBlocked = actions::finishBlocked,
                        onClose = ::dismiss,
                    )
                }
            ProgressScreen(
                active = active,
                rows = rows,
                callbacks = callbacks,
            )
        }
    }
