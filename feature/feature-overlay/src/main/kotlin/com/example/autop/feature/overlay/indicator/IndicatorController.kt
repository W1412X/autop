/**
 * 执行期状态指示的显示控制（ADR-0014）：智能体正在操作手机（Run 活跃、未暂停、未等待回答）时显示
 * 屏幕边缘光与顶部胶囊；暂停、等待用户回答或指示关闭时隐藏。窗口本身不可触摸、不可聚焦，不会挡住注入动作。
 */
package com.example.autop.feature.overlay.indicator

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunService
import com.example.autop.task.RunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 指示窗口的显示与隐藏（进程级）。 */
@Singleton
public class IndicatorController
    @Inject
    constructor(
        private val host: OverlayHost,
        private val runService: RunService,
        private val configProvider: ConfigProvider,
        dispatchers: DispatcherProvider,
        logger: Logger,
    ) {
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.main + logger.exceptionHandler("IndicatorController"))
        private var shown = false
        private var hideJob: Job? = null

        /** 内容是否处于“显示”态；变为 false 时内容淡出，淡出结束后才移除窗口。 */
        private val contentVisible = MutableStateFlow(false)

        /** 订阅 Run 与配置，按规则显示或隐藏指示窗口（幂等）。 */
        public fun start() {
            scope.launch {
                combine(runService.observeActive(), configProvider.observe()) { active, cfg ->
                    active to cfg[UiConfig.INDICATOR].enabled
                }.collect { (active, enabled) -> apply(enabled && isOperating(active)) }
            }
        }

        /** 指示窗口工厂（app 组装层注册到 `OverlayWindowManager`）。 */
        public fun indicatorFactory(): (Context, OverlayLifecycleOwner) -> View =
            { windowContext, owner ->
                composeOverlayView(windowContext, owner) { IndicatorContent() }
            }

        private fun apply(visible: Boolean) {
            if (visible == shown) return
            shown = visible
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

        @Composable
        private fun IndicatorContent() {
            val active by runService.observeActive().collectAsState()
            val visible by contentVisible.collectAsState()
            val cfg = remember(configProvider) { configProvider.current()[UiConfig.INDICATOR] }
            IndicatorScreen(
                stepCount = active?.stepCount ?: 0,
                edgeGlow = cfg.edgeGlow,
                capsule = cfg.capsule,
                pulseMs = cfg.pulseMs,
                visible = visible,
            )
        }
    }

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
