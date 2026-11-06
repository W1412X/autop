package com.example.autop

import android.app.Application
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import com.example.autop.core.platform.AndroidLogger
import com.example.autop.core.platform.CrashLogger
import com.example.autop.device.UserActivityMonitor
import com.example.autop.device.VisualScreenParser
import com.example.autop.device.impl.overlay.OverlayWindowManager
import com.example.autop.device.impl.touch.TouchShieldController
import com.example.autop.feature.overlay.OverlayBallController
import com.example.autop.feature.overlay.OverlayWindowIds
import com.example.autop.feature.overlay.ball.BallMenuController
import com.example.autop.feature.overlay.indicator.IndicatorController
import com.example.autop.feature.overlay.panel.PanelController
import com.example.autop.feature.overlay.probe.ProbeController
import com.example.autop.feature.overlay.progress.ProgressController
import com.example.autop.feature.overlay.record.RecordController
import com.example.autop.task.impl.InterruptedRunRecovery
import com.example.autop.task.impl.di.AppScope
import com.example.autop.task.impl.store.AuditCleaner
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用入口：Hilt 聚合根（docs/tech/01 第 3 节）。
 * WP7 接线：悬浮球窗口工厂注册（FEATURE 不依赖 IMPL，注册在组装层做，docs/tech/05 §10），
 * 触摸检测（[UserActivityMonitor] 注入即启动探针与 a11y 两路，docs/tech/05 §8）。
 * WP10 接线：中断恢复标记（docs/tech/04 §3.11）与审计日志定期清理（docs/tech/07 §8）。
 * N182 接线：圆弧菜单（MENU）与任务进度悬浮窗（PROGRESS）工厂注册；
 * 进度窗的自动弹出与消息行随 [ProgressController] 注入即自启动。
 * N186 接线：触摸盾（[TouchShieldController]）最先注册与常驻显示，
 * 窗口按添加顺序垫底（同 type 后加者在上），球/菜单/面板/进度窗在盾之上照常交互。
 * 日志报错接线（questions.md N165）：CrashLogger 安装（未捕获异常落文件）与
 * 日志配置绑定（缓冲容量 + debug 日志开关随 `ui.debug` 变化生效）。
 */
@HiltAndroidApp
class AutopApplication : Application() {
    @Inject lateinit var overlayWindows: OverlayWindowManager

    @Inject lateinit var ballController: OverlayBallController

    @Inject lateinit var ballMenuController: BallMenuController

    @Inject lateinit var panelController: PanelController

    @Inject lateinit var progressController: ProgressController

    @Inject lateinit var probeController: ProbeController

    @Inject lateinit var recordController: RecordController

    @Inject lateinit var indicatorController: IndicatorController

    @Inject lateinit var configProvider: ConfigProvider

    @Inject lateinit var userActivityMonitor: UserActivityMonitor

    @Inject lateinit var touchShield: TouchShieldController

    @Inject lateinit var visionParser: VisualScreenParser

    @Inject lateinit var runRecovery: InterruptedRunRecovery

    @Inject lateinit var auditCleaner: AuditCleaner

    @Inject lateinit var androidLogger: AndroidLogger

    @Inject
    @AppScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(androidLogger)
        bindLoggingConfig()
        // N186：触摸盾最先注册并常驻（z 序垫底，开关走 spec.touchable）。
        touchShield.start()
        overlayWindows.register(OverlayWindowIds.BALL, ballController.ballFactory())
        overlayWindows.register(OverlayWindowIds.MENU, ballMenuController.menuFactory())
        overlayWindows.register(OverlayWindowIds.PANEL, panelController.panelFactory())
        overlayWindows.register(OverlayWindowIds.PROGRESS, progressController.progressFactory())
        overlayWindows.register(OverlayWindowIds.PROBE, probeController.probeFactory())
        overlayWindows.register(OverlayWindowIds.RECORD, recordController.recordFactory())
        overlayWindows.register(OverlayWindowIds.PICKER, recordController.pickerFactory())
        // ADR-0014：执行期指示在盾之上（不可触摸，不挡注入）。
        overlayWindows.register(OverlayWindowIds.INDICATOR, indicatorController.indicatorFactory())
        indicatorController.start()
        if (configProvider.current()[UiConfig.OVERLAY].enabled) {
            ballController.start()
        }
        auditCleaner.ensureScheduled()
        appScope.launch { runRecovery.markInterrupted() }
        // 视觉模型预热：模型资产约 72MB，首次加载要读资产＋校验＋建会话（含 NNAPI 编译），
        // 放在首次观测里会撞穿解析预算并抬高瞬时内存峰值（易超时/被系统回收），
        // 挪到启动后的空闲期做，首次 observe 直接命中已建好的会话。
        appScope.launch { visionParser.warmUp() }
    }

    /** 配置 → Logger 接线（questions.md N15/N165）：缓冲容量与 debug 开关随配置变化生效。 */
    private fun bindLoggingConfig() {
        appScope.launch {
            configProvider.observe().collect { snapshot ->
                val debug = snapshot[UiConfig.DEBUG]
                androidLogger.bufferCapacity = debug.logBufferSize
                androidLogger.verboseLogging = debug.verboseLogging
            }
        }
    }
}
