/**
 * 悬浮球状态持有者（docs/tech/08 §5.1）：聚合运行状态、无障碍连接与配置推导 [BallUiState]，
 * 并管理球位置（拖动、停靠、细条收拢、持久化）。
 *
 * 不用 `ViewModel`：悬浮球窗口随通道重建但状态属进程级，由 [com.example.autop.feature.overlay.OverlayBallController]
 * （@Singleton）持有，窗口重建不丢状态（见 questions.md）。
 */
package com.example.autop.feature.overlay.ball

import com.example.autop.agent.AgentEvent
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import com.example.autop.device.AccessibilityStatus
import com.example.autop.flow.RecordingSession
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 悬浮球状态与位置的推导、维护。 */
@Singleton
@Suppress("LongParameterList") // 状态推导的端口装配面（run/a11y/config/position/logger/dispatchers）
public class BallStateHolder
    @Inject
    constructor(
        private val runService: RunService,
        private val recording: RecordingSession,
        accessibility: AccessibilityStatus,
        private val configProvider: ConfigProvider,
        private val positionStore: BallPositionStore,
        private val logger: Logger,
        dispatchers: DispatcherProvider,
    ) {
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.default + logger.exceptionHandler("BallStateHolder"))

        /** 当前工具类别（`AgentEvent.ToolCallStreaming` 最近一次的映射；无工具时 null → OTHER）。 */
        private val toolCategory: Flow<ToolCategory?> =
            runService.observeActive().flatMapLatest { active ->
                if (active == null) {
                    flowOf(null)
                } else {
                    runService
                        .liveEvents(active.run.id)
                        .map { event -> (event as? AgentEvent.ToolCallStreaming)?.let { categoryOfTool(it.name) } }
                }
            }

        /** 球渲染状态。 */
        public val uiState: StateFlow<BallUiState> =
            combine(
                runService.observeActive(),
                accessibility.connected,
                configProvider.observe().map { configBits(it[AgentConfig.BUDGET].maxSteps, it) },
                toolCategory,
                recording.state.map { it.active },
            ) { active, connected, bits, tool, isRecording ->
                BallUiState(
                    mode = deriveBallMode(active, connected, bits.maxSteps, tool, isRecording),
                    breathMs = bits.breathMs,
                    motionEnabled = bits.motionEnabled,
                    doubleTapStops = bits.doubleTapStops,
                    attachScreenDefault = bits.attachScreenDefault,
                )
            }.stateIn(scope, SharingStarted.Eagerly, BallUiState())

        private val _position: MutableStateFlow<BallPosition> =
            MutableStateFlow(positionStore.load() ?: BallPosition(UNSET, UNSET))

        /** 球窗口位置（px）；未持久化且未摆放时为 [UNSET]。 */
        public val position: StateFlow<BallPosition> = _position.asStateFlow()

        /** 自动收拢定时器（半贴边静置 → 细条）。 */
        private var collapseJob: Job? = null

        /** 停靠几何缓存（收拢定时器用；拖动/摆放时刷新）。 */
        private var geom = Geom(0, 0, 0)

        private data class Geom(
            val screenWidthPx: Int,
            val ballSizePx: Int,
            val sliverPx: Int,
        )

        /** 当前活跃 Run（圆弧菜单条件项与进度窗共用）。 */
        public fun activeRun(): ActiveRunInfo? = runService.observeActive().value

        /** 拖动中：更新窗口位置（自由态，不持久化；顺带停掉收拢定时器）。 */
        public fun onMoved(
            x: Int,
            y: Int,
        ) {
            collapseJob?.cancel()
            _position.update { BallPosition(x, y) }
        }

        /**
         * 拖动结束：近边缘磁吸成半贴边态，否则自由停留（松手就地，不强制吸边）；
         * 持久化并按 `ui.overlay.displayMode` 收拢——edge=松手即收细条、
         * always=保持展开、auto=静置 [AUTO_COLLAPSE_MS] 自动收细条。
         */
        public fun onDragEnd(
            ballSizePx: Int,
            screenWidthPx: Int,
            sliverPx: Int,
            magnetPx: Int,
        ) {
            val current = _position.value
            if (current.x == UNSET) return
            geom = Geom(screenWidthPx, ballSizePx, sliverPx)
            val released = releasePosition(current.x, current.y, ballSizePx, screenWidthPx, magnetPx)
            _position.value = released
            positionStore.save(released)
            when (configProvider.current()[UiConfig.OVERLAY].displayMode) {
                DISPLAY_MODE_EDGE -> collapseNow()
                DISPLAY_MODE_ALWAYS -> collapseJob?.cancel()
                else -> scheduleCollapse()
            }
        }

        /** 立即收拢为细条（edge 显示方式）。 */
        private fun collapseNow() {
            collapseJob?.cancel()
            val current = _position.value
            val collapsed = collapseToSliver(current, geom.ballSizePx, geom.screenWidthPx, geom.sliverPx)
            if (collapsed != current) {
                _position.value = collapsed
                positionStore.save(collapsed)
            }
        }

        /** 唤出（细条/半贴边 → 自由态）：球整体回到屏内并持久化。 */
        public fun onReveal(
            screenWidthPx: Int,
            ballSizePx: Int,
        ) {
            val current = _position.value
            if (current.x == UNSET) return
            collapseJob?.cancel()
            val revealed = revealPosition(current, screenWidthPx, ballSizePx)
            _position.value = revealed
            positionStore.save(revealed)
        }

        /**
         * 摆放/钳制：无位置时摆右边缘偏上；已有位置按停靠锚在当前屏宽上重摆。
         * 均不持久化，停靠/唤出后才持久化。x 绑死在保存时的屏宽上，旋转后直接钳回
         * 会把贴边球放回屏幕中央——按 [BallPosition.side]/[BallPosition.dock] 重摆
         * 才能留在原来那侧的停靠/细条状态。
         */
        public fun placeDefault(
            screenWidthPx: Int,
            screenHeightPx: Int,
            ballSizePx: Int,
            sliverPx: Int,
        ) {
            geom = Geom(screenWidthPx, ballSizePx, sliverPx)
            val current = _position.value
            if (current.x == UNSET) {
                _position.value =
                    BallPosition(
                        x = (screenWidthPx - ballSizePx).coerceAtLeast(0),
                        y = screenHeightPx / DEFAULT_Y_DIVISOR,
                        side = BallSide.RIGHT,
                        dock = BallDock.NONE,
                    )
                return
            }
            val anchored = anchor(current, screenWidthPx, screenHeightPx, ballSizePx, sliverPx)
            if (anchored != current) _position.value = anchored
        }

        /** 停靠锚重摆（旋转/分屏改尺寸）。 */
        private fun anchor(
            current: BallPosition,
            screenWidthPx: Int,
            screenHeightPx: Int,
            ballSizePx: Int,
            sliverPx: Int,
        ): BallPosition {
            val dockedSide = current.side != BallSide.NONE
            val anchoredX =
                when {
                    dockedSide && current.dock == BallDock.SLIVER ->
                        sliverX(current.side, ballSizePx, screenWidthPx, sliverPx)
                    dockedSide && current.dock == BallDock.EDGE -> dockX(current.side, ballSizePx, screenWidthPx)
                    current.side == BallSide.LEFT -> 0
                    current.side == BallSide.RIGHT -> (screenWidthPx - ballSizePx).coerceAtLeast(0)
                    // 自由态瞬时位置（拖动中改尺寸）：普通钳回屏内。
                    else -> current.x.coerceIn(0, (screenWidthPx - ballSizePx).coerceAtLeast(0))
                }
            return current.copy(
                x = anchoredX,
                y = current.y.coerceIn(0, (screenHeightPx - ballSizePx).coerceAtLeast(0)),
            )
        }

        /** 半贴边静置 [AUTO_COLLAPSE_MS] → 细条（[BallDock.EDGE] 才收，其余无操作）。 */
        private fun scheduleCollapse() {
            collapseJob?.cancel()
            collapseJob =
                scope.launch {
                    delay(AUTO_COLLAPSE_MS)
                    val current = _position.value
                    val collapsed = collapseToSliver(current, geom.ballSizePx, geom.screenWidthPx, geom.sliverPx)
                    if (collapsed != current) {
                        _position.value = collapsed
                        positionStore.save(collapsed)
                    }
                }
        }

        private fun configBits(
            maxSteps: Int,
            snapshot: com.example.autop.core.config.ConfigSnapshot,
        ): ConfigBits =
            ConfigBits(
                maxSteps = maxSteps,
                breathMs = snapshot[UiConfig.OVERLAY].breathMs,
                motionEnabled = snapshot[UiConfig.APPEARANCE].motionEnabled,
                doubleTapStops = snapshot[UiConfig.OVERLAY].doubleTapStops,
                attachScreenDefault = snapshot[UiConfig.OVERLAY].attachScreenDefault,
            )

        /** 未摆放/未持久化的位置标记。 */
        public companion object {
            public const val UNSET: Int = Int.MIN_VALUE

            /** 默认摆放高度：屏高 / 3。 */
            private const val DEFAULT_Y_DIVISOR: Int = 3

            /** 半贴边静置收拢为细条的时长（ms）。 */
            public const val AUTO_COLLAPSE_MS: Long = 3000

            /** `ui.overlay.displayMode` 取值（edge=松手即收边细条 / always=保持展开）。 */
            private const val DISPLAY_MODE_EDGE: String = "edge"
            private const val DISPLAY_MODE_ALWAYS: String = "always"
        }
    }

/** 配置聚合（combine 的中间量）。 */
private data class ConfigBits(
    val maxSteps: Int,
    val breathMs: Long,
    val motionEnabled: Boolean,
    val doubleTapStops: Boolean,
    val attachScreenDefault: Boolean,
)
