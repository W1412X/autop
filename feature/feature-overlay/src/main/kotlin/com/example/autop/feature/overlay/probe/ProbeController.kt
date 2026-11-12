/**
 * 工具测试悬浮面板装配（docs/tech/08 §5.3，N198）：圆弧菜单「工具测试」展开，选工具 →
 * 可切任意页面 →「执行」在当前界面跑工具（真实执行，看输出定位问题）。执行时面板
 * **让路**（隐藏到结果回来再显示，避免挡住注入落点）；「导出日志」写缓存文件后经
 * FileProvider 拉起系统分享（同 N175 的对话导出），内容=探测记录+应用运行日志。
 * 窗口 `register` 由 app 组装层调用（FEATURE 不依赖 IMPL，docs/tech/01 §4）。
 */
package com.example.autop.feature.overlay.probe

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.FileProvider
import com.example.autop.agent.ToolProbeResult
import com.example.autop.agent.ToolProbeService
import com.example.autop.agent.ToolProbeTarget
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import com.example.autop.core.ui.overlay.OverlayLifecycleOwner
import com.example.autop.device.OverlayHost
import com.example.autop.device.ScreenLease
import com.example.autop.feature.overlay.OverlayWindowIds
import com.example.autop.feature.overlay.composeOverlayView
import com.example.autop.feature.overlay.panel.PanelRootView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 结果列表条数上限（更早的裁掉，探测历史仍在 ToolProber 里可导出）。 */
private const val MAX_RESULTS: Int = 30

/** 执行后面板让路时长（毫秒）：给用户一个看界面变化的窗口期。 */
private const val PASS_THROUGH_MS: Long = 2_000

/** 工具测试悬浮面板的注册、显示与执行（进程级）。 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions") // 装配类：端口+数据源+日志+调度，不可再减（N114）
public class ProbeController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val host: OverlayHost,
        private val prober: ToolProbeService,
        private val screenLease: ScreenLease,
        private val configProvider: ConfigProvider,
        private val logger: Logger,
        dispatchers: DispatcherProvider,
    ) {
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.main + logger.exceptionHandler("ProbeController"))

        private val visibleState = MutableStateFlow(false)

        /** 测试面板是否可见（用户意愿；执行中的临时隐藏不改它）。 */
        public val visible: StateFlow<Boolean> = visibleState.asStateFlow()

        private val targetsState = MutableStateFlow(prober.targets())

        private val selectedState = MutableStateFlow<ToolProbeTarget?>(targetsState.value.firstOrNull())

        /** 参数逐键编辑值（N207：键=参数名，空串=不传）。 */
        private val argsState = MutableStateFlow(selectedState.value?.let(::defaultValuesOf).orEmpty())

        private val runningState = MutableStateFlow(false)

        /** 执行被拦截的原因（面板状态提示；null=无提示，tool_optimize D20）。 */
        private val blockedState = MutableStateFlow<ProbeBlocked?>(null)

        /** 结果列表（新→旧，派生自探测历史，与设置页记录页同源；N207）。 */
        private val resultsState = MutableStateFlow<List<ToolProbeResult>>(emptyList())

        /** 面板内容工厂（app 组装层注册到 `OverlayWindowManager`）。 */
        public fun probeFactory(): (Context, OverlayLifecycleOwner) -> View =
            { windowContext, owner ->
                PanelRootView(
                    windowContext,
                    onOutsideTouch = {},
                    onBack = ::dismiss,
                ).apply {
                    addView(composeOverlayView(windowContext, owner) { ProbeContent() })
                }
            }

        /** 展开工具测试面板（已显示时不重复 show）；顺带同步探测历史。调试开关关闭时不展开（tool_optimize D20）。 */
        public fun show() {
            if (!debugEnabled()) return
            if (visibleState.value) return
            visibleState.value = true
            refreshResults()
            host.show(OverlayWindowIds.PROBE, probeSpec(context))
        }

        /** 收起工具测试面板。 */
        public fun dismiss() {
            if (!visibleState.value) return
            visibleState.value = false
            host.hide(OverlayWindowIds.PROBE)
        }

        /** 选中工具（参数值回到该工具的示例值，N207）。 */
        public fun selectTool(target: ToolProbeTarget) {
            selectedState.value = target
            argsState.value = defaultValuesOf(target)
        }

        /** 单个参数编辑（空串=不传该参数）。 */
        public fun updateArg(
            name: String,
            value: String,
        ) {
            argsState.update { it + (name to value) }
        }

        /** 参数全部重置为示例值。 */
        public fun resetArgs() {
            selectedState.value?.let { argsState.value = defaultValuesOf(it) }
        }

        /**
         * 执行选中工具（N198）：面板先让路（隐藏），真实执行后延迟回来显示——
         * 注入落点不被面板窗口挡住，用户也留有看界面变化的窗口期。
         * 执行门控（tool_optimize D20）：调试开关关闭或有 Run 持有屏幕租约时不执行，
         * 在面板状态中给出提示；执行期间以 [PROBE_LEASE_OWNER] 持有屏幕租约。
         */
        public fun runProbe() {
            val target = selectedState.value ?: return
            if (runningState.value) return
            when {
                !debugEnabled() -> {
                    blockedState.value = ProbeBlocked.DEBUG_OFF
                    return
                }
                screenLease.holder.value != null -> {
                    blockedState.value = ProbeBlocked.RUN_ACTIVE
                    return
                }
            }
            blockedState.value = null
            runningState.value = true
            scope.launch {
                host.hide(OverlayWindowIds.PROBE)
                screenLease.withLease(PROBE_LEASE_OWNER) {
                    prober.probe(target.name, argsJsonOf(target.params, argsState.value))
                }
                refreshResults()
                delay(PASS_THROUGH_MS)
                if (visibleState.value) host.show(OverlayWindowIds.PROBE, probeSpec(context))
                runningState.value = false
            }
        }

        /** 导出诊断日志（N198）：探测记录+运行日志写缓存文件后拉起系统分享（N175 同款）。 */
        public fun exportDiagnostics() {
            runCatching {
                val dir = File(context.cacheDir, "export").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(dir, "autop-export-$stamp.log")
                file.writeText(prober.diagnosticsText(), Charsets.UTF_8)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val send =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                context.startActivity(send)
            }.onFailure { e -> logger.e("Probe", "probe_export_failed", "error" to (e.message ?: e.toString())) }
        }

        /** 结果列表与探测历史同源（新→旧；设置页记录页读同一份 history，N207）。 */
        private fun refreshResults() {
            resultsState.value = prober.history().asReversed().take(MAX_RESULTS)
        }

        /** 调试模式开关（`ui.debug.verboseLogging`，tool_optimize D20）。 */
        private fun debugEnabled(): Boolean = configProvider.current()[UiConfig.DEBUG].verboseLogging

        /** 窗口内容：状态打包接 [ProbeScreen]。 */
        @Composable
        private fun ProbeContent() {
            val targets by targetsState.collectAsState()
            val selected by selectedState.collectAsState()
            val args by argsState.collectAsState()
            val running by runningState.collectAsState()
            val results by resultsState.collectAsState()
            val blocked by blockedState.collectAsState()
            val callbacks =
                remember(this) {
                    ProbeCallbacks(
                        onSelect = ::selectTool,
                        onArgChange = ::updateArg,
                        onResetArgs = ::resetArgs,
                        onRun = ::runProbe,
                        onExport = ::exportDiagnostics,
                        onClose = ::dismiss,
                    )
                }
            ProbeScreen(
                state =
                    ProbeUiState(
                        targets = targets,
                        selected = selected,
                        args = args,
                        running = running,
                        results = results,
                        blocked = blocked,
                    ),
                callbacks = callbacks,
            )
        }

        public companion object {
            /** 工具自测执行期间的屏幕租约持有者（`ScreenLease.withLease`，tool_optimize D20）。 */
            public const val PROBE_LEASE_OWNER: String = "probe"
        }
    }
