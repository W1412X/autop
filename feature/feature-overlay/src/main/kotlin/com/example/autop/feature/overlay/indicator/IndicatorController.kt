/**
 * 执行期状态指示的显示控制（ADR-0014 + 运行控制条）：
 * - 边缘光（[OverlayWindowIds.INDICATOR]，整屏不可触摸）：Run 活跃、未暂停、未等待回答时显示。
 * - 运行控制条（[OverlayWindowIds.RUN_BAR]，顶部窄条可触摸）：有活跃 Run 即显示（暂停/
 *   等待也有操作出口），点操作信息展开详情、按钮执行暂停/继续/中止。
 * - 运行详情（[OverlayWindowIds.RUN_DETAIL]）：条下的消息流观测面板，展开时显示、再点收起。
 * 悬浮球在控制条可见时由组装层隐藏（[barVisible] 同源），Run 收口自动回显；控制条窗口
 * 不可聚焦、只包内容（N222：注入落点压到控制条时按占位让它路，见 device-impl 的
 * `OverlayWindowRegistry.addFootprint`，注入结束即恢复）。
 */
package com.example.autop.feature.overlay.indicator

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.autop.agent.PauseReason
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import com.example.autop.core.ui.overlay.OverlayLifecycleOwner
import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlayHost
import com.example.autop.device.OverlaySpec
import com.example.autop.feature.overlay.OverlayWindowIds
import com.example.autop.feature.overlay.composeOverlayView
import com.example.autop.feature.overlay.panel.PanelRunActions
import com.example.autop.feature.overlay.progress.ProgressCallbacks
import com.example.autop.feature.overlay.progress.ProgressController
import com.example.autop.flow.FlowProgressFeed
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunService
import com.example.autop.task.RunStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 指示窗口的显示与隐藏（进程级）。 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions") // 装配类：端口 + 动作 + 消息源 + 三窗口显隐面，职责单一
public class IndicatorController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val host: OverlayHost,
        private val runService: RunService,
        private val configProvider: ConfigProvider,
        private val actions: PanelRunActions,
        private val progress: ProgressController,
        private val flowFeed: FlowProgressFeed,
        dispatchers: DispatcherProvider,
        logger: Logger,
    ) {
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.main + logger.exceptionHandler("IndicatorController"))
        private var glowShown = false
        private var barShown = false
        private var hideJob: Job? = null
        private var barHideJob: Job? = null

        /** 最近一次活跃 Run（收口后淡出期间仍渲染最后状态，防内容瞬间清空）。 */
        private var lastActive: ActiveRunInfo? = null

        /** 边缘光内容是否处于“显示”态；变为 false 时内容淡出，淡出结束后才移除窗口。 */
        private val contentVisible = MutableStateFlow(false)

        /** 控制条内容是否处于“显示”态；淡出结束后才移除窗口。 */
        private val barContentVisible = MutableStateFlow(false)

        /** 详情面板展开（点操作信息切换；控制条隐藏时收起）。 */
        private val detailExpanded = MutableStateFlow(false)

        /** 控制条可见中（悬浮球显隐同源用：条可见则藏球）。 */
        private val barVisibleState = MutableStateFlow(false)

        /** 控制条是否可见。 */
        public val barVisible: StateFlow<Boolean> = barVisibleState.asStateFlow()

        /** 订阅 Run 与配置，按规则显示或隐藏各窗口（幂等）。 */
        public fun start() {
            scope.launch {
                val indicator = configProvider.observe().map { it[UiConfig.INDICATOR] }
                combine(runService.observeActive(), indicator) { active, ind ->
                    VisibilityInput(active = active, enabled = ind.enabled, barOn = ind.capsule, glowOn = ind.edgeGlow)
                }.collect { input -> applyVisibility(input) }
            }
            scope.launch { autoExpandOnInteraction() }
        }

        /**
         * 等待回答/被拦截的迁移沿自动展开详情面板（执行中交互走控制条＋消息条体系，
         * 就地完成回答/决策，替代旧进度悬浮窗的自动弹出）。
         */
        private suspend fun autoExpandOnInteraction() {
            var lastQuestion: String? = null
            var lastBlocked = false
            runService.observeActive().collect { info ->
                val question = info?.run?.waitingQuestion
                val blocked = info?.run?.pauseReason == PauseReason.POLICY_BLOCKED
                if (questionAppeared(question, lastQuestion) || blockAppeared(blocked, lastBlocked)) {
                    expandDetail()
                }
                lastQuestion = question
                lastBlocked = blocked
            }
        }

        /** 边缘光窗口工厂（app 组装层注册到 `OverlayWindowManager`）。 */
        public fun indicatorFactory(): (Context, OverlayLifecycleOwner) -> View =
            { windowContext, owner ->
                composeOverlayView(windowContext, owner) { IndicatorContent() }
            }

        /** 运行控制条窗口工厂。 */
        public fun runBarFactory(): (Context, OverlayLifecycleOwner) -> View =
            { windowContext, owner ->
                composeOverlayView(windowContext, owner) { RunBarContent() }
            }

        /** 运行详情窗口工厂。 */
        public fun runDetailFactory(): (Context, OverlayLifecycleOwner) -> View =
            { windowContext, owner ->
                composeOverlayView(windowContext, owner) { RunDetailContent() }
            }

        /** 控制条与边缘光的显隐；控制条隐藏时一并收起详情面板。 */
        private fun applyVisibility(input: VisibilityInput) {
            if (input.active != null) lastActive = input.active
            applyBar(input.enabled && input.barOn && input.active != null)
            applyGlow(input.enabled && input.glowOn && isOperating(input.active))
        }

        private fun applyBar(visible: Boolean) {
            if (visible == barShown) return
            barShown = visible
            barVisibleState.value = visible
            barHideJob?.cancel()
            if (visible) {
                barContentVisible.value = true
                host.show(OverlayWindowIds.RUN_BAR, runBarSpec(context))
            } else {
                collapseDetail()
                barHideJob =
                    scope.launch {
                        delay(BAR_FADE_OUT_MS)
                        host.hide(OverlayWindowIds.RUN_BAR)
                    }
            }
        }

        private fun applyGlow(visible: Boolean) {
            if (visible == glowShown) return
            glowShown = visible
            hideJob?.cancel()
            if (visible) {
                contentVisible.value = true
                host.show(OverlayWindowIds.INDICATOR, indicatorSpec())
            } else {
                contentVisible.value = false
                hideJob =
                    scope.launch {
                        delay(INDICATOR_FADE_OUT_MS)
                        host.hide(OverlayWindowIds.INDICATOR)
                    }
            }
        }

        /** 操作信息点按：展开/收起详情面板。 */
        private fun toggleDetail() {
            if (detailExpanded.value) {
                collapseDetail()
            } else {
                expandDetail()
            }
        }

        private fun expandDetail() {
            if (detailExpanded.value) return
            detailExpanded.value = true
            host.show(OverlayWindowIds.RUN_DETAIL, runDetailSpec(context))
        }

        private fun collapseDetail() {
            if (!detailExpanded.value) return
            detailExpanded.value = false
            host.hide(OverlayWindowIds.RUN_DETAIL)
        }

        /** 控制条按钮：暂停/继续走同一切换入口；中止先收起详情再走安全停止（N191/N217）。 */
        private fun onAction(action: RunBarAction) {
            when (action) {
                RunBarAction.PAUSE, RunBarAction.RESUME -> actions.pauseResume()
                RunBarAction.STOP -> {
                    collapseDetail()
                    actions.stop()
                }
            }
        }

        @Composable
        private fun IndicatorContent() {
            val visible by contentVisible.collectAsState()
            val cfg = remember(configProvider) { configProvider.current()[UiConfig.INDICATOR] }
            IndicatorScreen(edgeGlow = cfg.edgeGlow, pulseMs = cfg.pulseMs, visible = visible)
        }

        @Composable
        private fun RunBarContent() {
            val active by runService.observeActive().collectAsState()
            val pending by actions.controlIntent.collectAsState()
            val visible by barContentVisible.collectAsState()
            val expanded by detailExpanded.collectAsState()
            val current = active ?: lastActive ?: return
            RunBarScreen(
                active = current,
                expanded = expanded,
                visible = visible,
                pending = pending,
                onToggleExpand = ::toggleDetail,
                onAction = ::onAction,
            )
        }

        @Composable
        private fun RunDetailContent() {
            val rows by progress.rowsState.collectAsState()
            val active by runService.observeActive().collectAsState()
            val pending by actions.controlIntent.collectAsState()
            val callbacks =
                remember(this) {
                    ProgressCallbacks(
                        onAnswer = actions::answer,
                        onPauseResume = actions::pauseResume,
                        onStop = actions::stop,
                        onStopFlow = {
                            if (!actions.ignoreWhileInjecting(PanelRunActions.ACTION_STOP_FLOW)) {
                                val activeNow = runService.observeActive().value
                                val rootRunId = activeNow?.run?.id
                                if (rootRunId != null) flowFeed.cancel(rootRunId)
                            }
                        },
                        onOpenThread = {},
                        onSteer = actions::steer,
                        onContinueBlocked = actions::continueBlocked,
                        onFinishBlocked = actions::finishBlocked,
                        onClose = ::collapseDetail,
                    )
                }
            RunDetailScreen(active = active, rows = rows, callbacks = callbacks, intent = pending)
        }
    }

/** 可见性输入（Run 快照 + 指示配置）。 */
private data class VisibilityInput(
    val active: ActiveRunInfo?,
    val enabled: Boolean,
    val barOn: Boolean,
    val glowOn: Boolean,
)

/** 等待回答出现（迁移沿：null → 非 null）。 */
private fun questionAppeared(
    question: String?,
    lastQuestion: String?,
): Boolean = question != null && lastQuestion == null

/** 被拦截出现（迁移沿：false → true）。 */
private fun blockAppeared(
    blocked: Boolean,
    lastBlocked: Boolean,
): Boolean = blocked && !lastBlocked

/** 智能体是否正在操作手机：有活跃 Run，且未暂停、未等待用户回答（与触摸盾的屏蔽态一致）。 */
internal fun isOperating(active: ActiveRunInfo?): Boolean {
    val run = active?.run ?: return false
    return run.status != RunStatus.PAUSED && run.waitingQuestion == null
}

/** 指示窗口 spec：铺满整个屏幕（含状态栏与导航栏），不可触摸，不可聚焦。 */
internal fun indicatorSpec(): OverlaySpec =
    OverlaySpec(
        x = 0,
        y = 0,
        width = WindowManager.LayoutParams.MATCH_PARENT,
        height = WindowManager.LayoutParams.MATCH_PARENT,
        touchable = false,
        focusable = false,
        gravity = OverlayGravity.TOP_LEFT,
        fullScreen = true,
    )
