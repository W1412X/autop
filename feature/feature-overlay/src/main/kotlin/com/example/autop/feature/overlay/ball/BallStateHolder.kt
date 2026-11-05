/**
 * 悬浮球状态持有者（docs/tech/08 §5.1）：聚合运行状态、无障碍连接与配置推导 [BallUiState]，
 * 并管理球位置（拖动、吸附、持久化）。
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
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
import javax.inject.Inject
import javax.inject.Singleton

/** 悬浮球状态与位置的推导、维护。 */
@Singleton
@Suppress("LongParameterList") // 状态推导的端口装配面（run/a11y/config/position/logger/dispatchers）
public class BallStateHolder
    @Inject
    constructor(
        private val runService: RunService,
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
            ) { active, connected, bits, tool ->
                BallUiState(
                    mode = deriveBallMode(active, connected, bits.maxSteps, tool),
                    breathMs = bits.breathMs,
                    motionEnabled = bits.motionEnabled,
                    doubleTapStops = bits.doubleTapStops,
                    edgeHideRatio = bits.edgeHideRatio,
                    attachScreenDefault = bits.attachScreenDefault,
                )
            }.stateIn(scope, SharingStarted.Eagerly, BallUiState())

        private val _position: MutableStateFlow<BallPosition> =
            MutableStateFlow(positionStore.load() ?: BallPosition(UNSET, UNSET))

        /** 球窗口位置（px）；未持久化且未摆放时为 [UNSET]。 */
        public val position: StateFlow<BallPosition> = _position.asStateFlow()

        /** 当前活跃 Run（圆弧菜单条件项与进度窗共用）。 */
        public fun activeRun(): ActiveRunInfo? = runService.observeActive().value

        /** 拖动中：更新窗口位置（不持久化）。 */
        public fun onMoved(
            x: Int,
            y: Int,
        ) {
            _position.update { BallPosition(x, y) }
        }

        /** 拖动结束：吸附到最近左右边并持久化（docs/tech/08 §5.1，N217 收边只露一小点）。 */
        public fun onSnap(
            ballSizePx: Int,
            screenWidthPx: Int,
            edgeHideRatio: Double,
        ) {
            val current = _position.value
            if (current.x == UNSET) return
            val snapped = snapTo(current.x.toFloat(), current.y, ballSizePx, screenWidthPx, edgeHideRatio)
            _position.value = snapped
            positionStore.save(snapped)
        }

        /** 贴边隐藏态点按唤出（N217）：球整体回到屏内并持久化。 */
        public fun onReveal(
            screenWidthPx: Int,
            ballSizePx: Int,
        ) {
            val current = _position.value
            if (current.x == UNSET) return
            val revealed = revealPosition(current, screenWidthPx, ballSizePx)
            _position.value = revealed
            positionStore.save(revealed)
        }

        /**
         * 摆放/钳制：无位置时摆右边缘偏上；已有位置按贴边锚在当前屏宽上重摆。
         * 均不持久化，吸附/唤出后才持久化。N217：x 绑死在保存时的屏宽上，旋转后
         * 直接钳回会把贴边球放回屏幕中央——按 [BallPosition.side]/[BallPosition.hidden]
         * 重摆才能留在原来那侧的收边/唤出状态。
         */
        public fun placeDefault(
            screenWidthPx: Int,
            screenHeightPx: Int,
            ballSizePx: Int,
        ) {
            val current = _position.value
            if (current.x == UNSET) {
                _position.value =
                    BallPosition(
                        x = (screenWidthPx - ballSizePx).coerceAtLeast(0),
                        y = screenHeightPx / DEFAULT_Y_DIVISOR,
                        side = BallSide.RIGHT,
                        hidden = false,
                    )
                return
            }
            val hide = hidePx(ballSizePx, configProvider.current()[UiConfig.OVERLAY].edgeHideRatio)
            val anchoredX =
                when {
                    current.hidden && current.side == BallSide.LEFT -> -hide
                    current.hidden && current.side == BallSide.RIGHT -> screenWidthPx - ballSizePx + hide
                    current.side == BallSide.LEFT -> 0
                    current.side == BallSide.RIGHT -> (screenWidthPx - ballSizePx).coerceAtLeast(0)
                    // 未吸附的瞬时位置（拖动中改尺寸）：普通钳回屏内。
                    else -> current.x.coerceIn(0, (screenWidthPx - ballSizePx).coerceAtLeast(0))
                }
            val placed =
                BallPosition(
                    x = anchoredX,
                    y = current.y.coerceIn(0, (screenHeightPx - ballSizePx).coerceAtLeast(0)),
                    side = current.side,
                    hidden = current.hidden,
                )
            if (placed != current) _position.value = placed
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
                edgeHideRatio = snapshot[UiConfig.OVERLAY].edgeHideRatio,
                attachScreenDefault = snapshot[UiConfig.OVERLAY].attachScreenDefault,
            )

        /** 未摆放/未持久化的位置标记。 */
        public companion object {
            public const val UNSET: Int = Int.MIN_VALUE

            /** 默认摆放高度：屏高 / 3。 */
            private const val DEFAULT_Y_DIVISOR: Int = 3
        }
    }

/** 配置聚合（combine 的中间量）。 */
private data class ConfigBits(
    val maxSteps: Int,
    val breathMs: Long,
    val motionEnabled: Boolean,
    val doubleTapStops: Boolean,
    val edgeHideRatio: Double,
    val attachScreenDefault: Boolean,
)
