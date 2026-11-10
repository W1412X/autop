package com.example.autop

import android.app.Application
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.TaskConfig
import com.example.autop.core.config.UiConfig
import com.example.autop.core.platform.AndroidLogger
import com.example.autop.core.platform.CrashLogger
import com.example.autop.device.PairingDiscovery
import com.example.autop.device.UpdateChecker
import com.example.autop.device.UserActivityMonitor
import com.example.autop.device.VisualScreenParser
import com.example.autop.device.impl.overlay.OverlayWindowManager
import com.example.autop.device.impl.touch.TouchShieldController
import com.example.autop.feature.overlay.OverlayBallController
import com.example.autop.feature.overlay.OverlayWindowIds
import com.example.autop.feature.overlay.ball.BallMenuController
import com.example.autop.feature.overlay.indicator.IndicatorController
import com.example.autop.feature.overlay.pairing.PairingCardController
import com.example.autop.feature.overlay.panel.PanelController
import com.example.autop.feature.overlay.probe.ProbeController
import com.example.autop.feature.overlay.progress.ProgressController
import com.example.autop.feature.overlay.record.RecordController
import com.example.autop.task.impl.InterruptedRunRecovery
import com.example.autop.task.impl.di.AppScope
import com.example.autop.task.impl.host.AgentHostStarter
import com.example.autop.task.impl.schedule.AlarmSync
import com.example.autop.task.impl.schedule.TriggerHub
import com.example.autop.task.impl.store.AuditCleaner
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 应用入口：Hilt 聚合根（docs/tech/01 第 3 节）。
 * WP7 接线：悬浮球窗口工厂注册（FEATURE 不依赖 IMPL，注册在组装层做，docs/tech/05 §10），
 * 触摸检测（[UserActivityMonitor] 注入即启动探针与 a11y 两路，docs/tech/05 §8）。
 * WP10 接线：中断恢复标记（docs/tech/04 §3.11）与审计日志定期清理（docs/tech/07 §8）。
 * N182 接线：圆弧菜单（MENU）与任务进度悬浮窗（PROGRESS）工厂注册；
 * 消息行随 [ProgressController] 注入即自启动（进度窗自动弹出已弃用 N218：
 * 执行中交互由顶部控制条＋消息条接管，见 IndicatorController）。
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

    @Inject lateinit var pairingDiscovery: PairingDiscovery

    @Inject lateinit var updateChecker: UpdateChecker

    @Inject lateinit var pairingCardController: PairingCardController

    @Inject lateinit var alarmSync: AlarmSync

    @Inject lateinit var triggerHub: TriggerHub

    @Inject lateinit var hostStarter: AgentHostStarter

    @Inject lateinit var runRecovery: InterruptedRunRecovery

    @Inject lateinit var auditCleaner: AuditCleaner

    @Inject lateinit var androidLogger: AndroidLogger

    @Inject lateinit var dispatchers: DispatcherProvider

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
        overlayWindows.register(OverlayWindowIds.PAIRING, pairingCardController.cardFactory())
        // ADR-0014：执行期指示在盾之上（边缘光不可触摸不挡注入；控制条可触摸、只包内容）。
        overlayWindows.register(OverlayWindowIds.INDICATOR, indicatorController.indicatorFactory())
        overlayWindows.register(OverlayWindowIds.RUN_BAR, indicatorController.runBarFactory())
        overlayWindows.register(OverlayWindowIds.RUN_DETAIL, indicatorController.runDetailFactory())
        indicatorController.start()
        // 悬浮球显隐随 ui.overlay.enabled 实时生效（菜单"隐藏悬浮球"/设置页开关立即收放）；
        // 运行期顶部控制条承担提示与停止入口时隐藏悬浮球（功能保留，Run 收口自动回显）。
        appScope.launch {
            combine(
                configProvider.observe().map { it[UiConfig.OVERLAY].enabled },
                indicatorController.barVisible,
            ) { enabled, barVisible -> enabled && !barVisible }
                .distinctUntilChanged()
                .collect { show -> if (show) ballController.start() else ballController.hide() }
        }
        // 无线调试配对自动发现（未配对时后台监听可配对服务，发现即通知；配对成功自动停用）。
        pairingDiscovery.start(appScope)
        // 应用更新静默检测（24h 节流，IO 协程，不阻塞界面；发现新版本由设置页横幅提示）。
        updateChecker.start(appScope)
        // 配对卡：发现即可配对设备即浮出输入小窗（零跳转，不依赖通知权限）。
        pairingCardController.start()
        // WP8.4：重启收口先行，未收口 Run 全部关闭后才允许任何新 Run 入队；
        // 闹钟同步、条件触发与常驻服务都排在收口之后（要求主线程的部分回 main 调度）。
        appScope.launch {
            runRecovery.closeUnfinished()
            withContext(dispatchers.main) {
                // 定时任务闹钟同步（2026-11-09 修复"到点不执行"）：任务变更即重排闹钟，首值覆盖启动重排。
                alarmSync.start(appScope)
                // 条件触发事件订阅（2026-11-09 修复"条件不触发"）：通知/前台/充电/网络/解锁事件流接入。
                triggerHub.start()
            }
            // 常驻后台随 task.host.persistent 生效：开启即拉起前台服务（启动首值也覆盖），
            // 进程不死才能持续收充电/网络/切前台等条件事件；关闭由服务自评停服。
            configProvider
                .observe()
                .map { it[TaskConfig.HOST].persistent }
                .distinctUntilChanged()
                .collect { persistent -> if (persistent) hostStarter.ensureRunning() }
        }
        auditCleaner.ensureScheduled()
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
