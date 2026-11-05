/**
 * 快照采集（docs/tech/05 §2.3）：窗口按 layer 降序、先序遍历、
 * `device.capture.maxNodes` 截断；不保存 AccessibilityNodeInfo 引用。
 */
package com.example.autop.device.impl

import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.runCatchingCancellable
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceCaptureSection
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType
import com.example.autop.device.core.ContentArea
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import android.graphics.Rect as AndroidRect

/** 屏幕快照采集器。 */
@Singleton
@Suppress("LongParameterList") // 装配类：服务/配置/时钟/前台/内容区/Context 均为采集必需，不可再减（N114 口径）
public class SnapshotCapturer
    @Inject
    constructor(
        private val holder: ServiceHolder,
        private val configProvider: ConfigProvider,
        private val clock: Clock,
        private val foreground: ForegroundTracker,
        private val contentAreas: ContentAreaProvider,
        @ApplicationContext private val context: Context,
    ) {
        /**
         * 采集当前屏幕快照（`device.capture.timeoutMs` 超时 → `device.timeout`）；
         * 应用窗口空树按 `device.capture.emptyTreeRetries/emptyTreeRetryDelayMs` 重试（R1/A3）。
         */
        public suspend fun capture(): Outcome<ScreenSnapshot> {
            val service =
                holder.current()
                    ?: return Outcome.Err(
                        AppError(
                            code = DeviceErrorCodes.SERVICE_UNAVAILABLE,
                            message = "accessibility service not connected",
                            retryable = true,
                        ),
                    )
            val cfg = configProvider.current()[DeviceConfig.CAPTURE]
            return captureWithEmptyTreeRetry(
                retries = cfg.emptyTreeRetries,
                delayMs = cfg.emptyTreeRetryDelayMs,
                emptyTree = ::hasEmptyAppTree,
                capture = { captureOnce(service, cfg) },
                exhausted = { emptyTreeUnavailable() },
            )
        }

        private suspend fun captureOnce(
            service: AutopAccessibilityService,
            cfg: DeviceCaptureSection,
        ): Outcome<ScreenSnapshot> =
            try {
                runCatchingCancellable(DeviceErrorCodes.SERVICE_UNAVAILABLE) {
                    withTimeout(cfg.timeoutMs) { captureWindows(service, cfg) }
                }
            } catch (e: TimeoutCancellationException) {
                Outcome.Err(
                    AppError(
                        code = DeviceErrorCodes.TIMEOUT,
                        message = "snapshot capture exceeded ${cfg.timeoutMs}ms",
                        retryable = true,
                        cause = e,
                    ),
                )
            }

        /** 空树重试耗尽（docs/tech/05 §2.3）。N192：附降级指引（部分应用屏蔽无障碍节点）。 */
        private fun emptyTreeUnavailable(): Outcome<ScreenSnapshot> =
            Outcome.Err(
                AppError(
                    code = DeviceErrorCodes.SERVICE_UNAVAILABLE,
                    message =
                        "应用窗口节点树为空（该应用可能屏蔽了无障碍节点），视觉合成树兑底未生效；" +
                            "可改用 screenshot 查看界面后用 tap 按坐标点击、swipe 按坐标滑动继续操作",
                    retryable = true,
                ),
            )

        private fun captureWindows(
            service: AutopAccessibilityService,
            cfg: DeviceCaptureSection,
        ): ScreenSnapshot {
            val state = CaptureState(cfg.maxNodes)
            val windows = service.windowList().sortedByDescending { it.layer }
            val winSnaps = windows.map { windowSnapshotOf(it, state) }
            return buildSnapshot(winSnaps, state.nodes, state.truncated)
        }

        private fun windowSnapshotOf(
            win: AccessibilityWindowInfo,
            state: CaptureState,
        ): WindowSnapshot {
            var rootIdx: Int? = null
            val root: AccessibilityNodeInfo? = win.root
            if (root != null && !state.truncated) {
                rootIdx = state.nodes.size
                useNode(root) { collectNode(it, null, 0, state) }
            }
            val bounds = AndroidRect()
            win.getBoundsInScreen(bounds)
            return WindowSnapshot(
                windowId = win.id,
                type = typeOf(win),
                layer = win.layer,
                packageName = root?.packageName?.toString(),
                title = win.title?.toString(),
                bounds = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                active = win.isActive,
                focused = win.isFocused,
                rootIdx = rootIdx,
            )
        }

        private fun collectNode(
            node: AccessibilityNodeInfo,
            parentIdx: Int?,
            childIndex: Int,
            state: CaptureState,
        ) {
            if (state.truncated) return
            val idx = state.nodes.size
            state.nodes += NodeSnapshotReader.read(node, idx, parentIdx, childIndex)
            if (state.nodes.size >= state.maxNodes) {
                state.truncated = true
                return
            }
            val childIdxs = mutableListOf<Int>()
            var i = 0
            while (i < node.childCount && !state.truncated) {
                val child = node.getChild(i)
                if (child != null) {
                    val childIdx = state.nodes.size
                    useNode(child) { collectNode(it, idx, i, state) }
                    if (state.nodes.size > childIdx) childIdxs += childIdx
                }
                i++
            }
            state.nodes[idx] = state.nodes[idx].copy(children = childIdxs)
        }

        /**
         * 组装快照；默认空参即降级快照（N193）：无窗口/节点，屏幕尺寸、前台与锁屏
         * 信息照常（不依赖节点树），供 `ScreenReader.snapshotOrDegraded` 兜底。
         */
        public fun buildSnapshot(
            windows: List<WindowSnapshot> = emptyList(),
            nodes: List<NodeSnapshot> = emptyList(),
            truncated: Boolean = false,
        ): ScreenSnapshot {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            val metrics = metricsOf(display)
            val fg = foreground.current.value
            // N204：内容区随快照带出。降级快照（无窗口）回填最近缓存（与截图裁剪同源，
            // 避免图按缓存裁、坐标按全屏换算的双源不一致），仍不覆盖缓存；无缓存为 null=全屏。
            val area =
                if (windows.isEmpty()) {
                    contentAreas.current()
                } else {
                    ContentArea.of(metrics.widthPixels, metrics.heightPixels, windows).also { contentAreas.update(it) }
                }
            return ScreenSnapshot(
                capturedAt = clock.nowMillis(),
                windows = windows,
                nodes = nodes,
                foregroundPackage = fg?.packageName,
                activity = fg?.activity,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                rotation = display?.rotation ?: 0,
                keyguardLocked = keyguardLocked(),
                contentArea = area,
                truncated = truncated,
            )
        }

        /**
         * 应用 Context 非可视化，`Context.getDisplay()`/`WindowManager.getCurrentWindowMetrics()`
         * 会抛 "Tried to obtain display from a Context not associated with one"；按提示用
         * [Context.createDisplayContext] 取显示尺寸（与 getBoundsInScreen 同一屏幕空间）。
         */
        private fun metricsOf(display: Display?): DisplayMetrics =
            if (display != null) {
                context.createDisplayContext(display).resources.displayMetrics
            } else {
                context.resources.displayMetrics
            }

        private fun keyguardLocked(): Boolean =
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

        private fun typeOf(win: AccessibilityWindowInfo): WindowType =
            when (win.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> WindowType.APPLICATION
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowType.INPUT_METHOD
                AccessibilityWindowInfo.TYPE_SYSTEM -> WindowType.SYSTEM
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowType.ACCESSIBILITY_OVERLAY
                AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> WindowType.SPLIT_DIVIDER
                else -> WindowType.UNKNOWN
            }

        private class CaptureState(
            val maxNodes: Int,
        ) {
            val nodes = mutableListOf<NodeSnapshot>()
            var truncated: Boolean = false
        }
    }
