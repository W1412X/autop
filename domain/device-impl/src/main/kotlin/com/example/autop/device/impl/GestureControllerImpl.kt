/**
 * 动作执行（docs/tech/05 §2.4）：节点动作、手势与全局动作；
 * 每个动作前后标记自身动作窗口（触摸检测排除用）。
 */
package com.example.autop.device.impl

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Logger
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.GestureController
import com.example.autop.device.GlobalAction
import com.example.autop.device.InjectionResult
import com.example.autop.device.NodeActionOutcome
import com.example.autop.device.Rect
import com.example.autop.device.ResolvedNode
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellSkip
import com.example.autop.device.ShellState
import com.example.autop.device.UserActivityMonitor
import com.example.autop.device.impl.touch.TouchShieldController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "GestureController"

/** 注入通道留痕事件名：导出日志据此判断实际走了哪条通道（shell / 无障碍手势）。 */
private const val EVENT_CHANNEL = "inject_channel"

/** shell 注入通道（`input` 命令，与真手触摸无差别）。 */
private const val CHANNEL_SHELL = "shell"

/** 无障碍手势通道（dispatchGesture，部分系统弹窗会丢弃这类注入）。 */
private const val CHANNEL_A11Y = "accessibility"

/** 动作名：点击。 */
private const val OP_TAP = "tap"

/** 动作名：滑动。 */
private const val OP_SWIPE = "swipe"

/** 动作名：全局动作（返回 / 主屏 / 最近任务）。 */
private const val OP_GLOBAL_ACTION = "global_action"

/** KeyEvent KEYCODE_*（android.view.KeyEvent 常量值，shell keyevent 注入用）。 */
private const val KEYCODE_BACK: Int = 4
private const val KEYCODE_HOME: Int = 3
private const val KEYCODE_APP_SWITCH: Int = 187

/** 轨迹中间点密度：每 [SWIPE_STEP_PX] 像素一个点，最少 1 个、最多 [SWIPE_MAX_POINTS] 个。 */
private const val SWIPE_STEP_PX: Int = 150
private const val SWIPE_MAX_POINTS: Int = 8

/** shell 注入的一次尝试（WP7.4）：已完成，或因 [reason] 跳过、转走无障碍。 */
internal sealed interface ShellAttempt {
    data object Done : ShellAttempt

    data class Skipped(
        val reason: ShellSkip,
    ) : ShellAttempt
}

/** ShellAttempt → InjectionResult（WP7.4）：Done 走 shell；Skipped 回落无障碍并记录没走 shell 的原因。 */
internal fun injectionResultOf(
    attempt: ShellAttempt,
    accessibilityOk: Boolean,
): InjectionResult =
    when (attempt) {
        ShellAttempt.Done -> InjectionResult.shell(ok = true)
        is ShellAttempt.Skipped -> InjectionResult.accessibility(accessibilityOk, attempt.reason)
    }

/** [GestureController] 实现。 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions") // 装配类：服务+监视+配置+时钟+盾+shell 通道+日志，不可再减（N114）；双通道路由辅助计入函数数
public class GestureControllerImpl
    @Inject
    constructor(
        private val holder: ServiceHolder,
        private val activityMonitor: UserActivityMonitor,
        private val configProvider: ConfigProvider,
        private val clock: Clock,
        private val touchShield: TouchShieldController,
        private val shellInput: ShellInput,
        private val logger: Logger,
    ) : GestureController {
        /** 对目标节点执行无障碍动作（如 ACTION_CLICK）。 */
        override suspend fun performNodeAction(
            target: ResolvedNode,
            actionId: Int,
            args: Map<String, Any?>,
        ): Boolean = selfAction { target.performAction(actionId, args) }

        /** 点击（或长按）坐标点。N199：shell 通道优先（应用可丢弃无障碍注入事件），失败回落无障碍手势。 */
        override suspend fun tap(
            x: Int,
            y: Int,
            durationMs: Long,
        ): InjectionResult {
            val attempt = shellTap(x, y, durationMs)
            if (attempt is ShellAttempt.Done) return InjectionResult.shell(ok = true)
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            return injectionResultOf(attempt, stroke(path, durationMs, OP_TAP, pointRect(x, y)))
        }

        /**
         * 滑动手势。N199：shell 通道优先，失败回落无障碍手势（§5.2：通道选择不受抖动影响）。
         * [jitterPx] 只用于无障碍通道的轨迹折点；端点抖动由工具在选通道前算好。
         */
        override suspend fun swipe(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
            durationMs: Long,
            jitterPx: Int,
        ): InjectionResult {
            val attempt = shellSwipe(fromX, fromY, toX, toY, durationMs)
            if (attempt is ShellAttempt.Done) return InjectionResult.shell(ok = true)
            val path = buildSwipePath(fromX, fromY, toX, toY, jitterPx)
            return injectionResultOf(
                attempt,
                stroke(path, durationMs, OP_SWIPE, spanRect(fromX, fromY, toX, toY)),
            )
        }

        /** 当前焦点输入框；没有返回 null（§5.3）。 */
        override suspend fun focusedInput(): ResolvedNode? {
            val service = holder.current() ?: return null
            val node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            val snapshot = NodeSnapshotReader.read(node, idx = 0, parent = null, childIndex = 0)
            return DirectResolvedNode(snapshot, node.windowId, node)
        }

        /** 按路径定位快照里选中的节点并执行无障碍动作；动作不走输入管道，无需放行触摸盾。 */
        override suspend fun performAtPath(
            windowId: Int,
            path: List<Int>,
            expectedBounds: Rect,
            actionId: Int,
        ): NodeActionOutcome {
            val node = locate(windowId, path, expectedBounds) ?: return NodeActionOutcome.STALE
            return try {
                if (selfAction { node.performAction(actionId) }) NodeActionOutcome.DONE else NodeActionOutcome.REJECTED
            } finally {
                release(node)
            }
        }

        /** 沿子序号路径走到节点，只比对 bounds 判断是否过期（不做语义匹配）；找不到或已变化返回 null。 */
        private fun locate(
            windowId: Int,
            path: List<Int>,
            expectedBounds: Rect,
        ): AccessibilityNodeInfo? {
            val service = holder.current() ?: return null
            var current: AccessibilityNodeInfo =
                service.windowList().firstOrNull { it.id == windowId }?.root ?: return null
            for (position in path) {
                val child = if (position in 0 until current.childCount) current.getChild(position) else null
                release(current)
                current = child ?: return null
            }
            val bounds = android.graphics.Rect()
            current.getBoundsInScreen(bounds)
            val same =
                bounds.left == expectedBounds.l &&
                    bounds.top == expectedBounds.t &&
                    bounds.right == expectedBounds.r &&
                    bounds.bottom == expectedBounds.b
            if (!same) release(current)
            return current.takeIf { same }
        }

        private fun release(node: AccessibilityNodeInfo) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) node.recycle()
        }

        /** 构建滑动路径：在起止点间插入中间点并按 [jitterPx] 随机偏移，模拟真实手指。 */
        private fun buildSwipePath(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
            jitterPx: Int,
        ): Path {
            val path = Path()
            path.moveTo(fromX.toFloat(), fromY.toFloat())
            val dx = toX - fromX
            val dy = toY - fromY
            val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toInt()
            val intermediatePoints = (distance / SWIPE_STEP_PX).coerceIn(1, SWIPE_MAX_POINTS)
            val segments = intermediatePoints + 1
            val random = ThreadLocalRandom.current()
            for (i in 1 until segments) {
                val ratio = i.toFloat() / segments
                val baseX = fromX + (dx * ratio).toInt()
                val baseY = fromY + (dy * ratio).toInt()
                val offsetX = if (jitterPx > 0) random.nextInt(-jitterPx, jitterPx + 1) else 0
                val offsetY = if (jitterPx > 0) random.nextInt(-jitterPx, jitterPx + 1) else 0
                path.lineTo((baseX + offsetX).toFloat(), (baseY + offsetY).toFloat())
            }
            path.lineTo(toX.toFloat(), toY.toFloat())
            return path
        }

        /** 点击的注入区域（一个像素）：只封锁落点所在的自家窗口（P2）。 */
        private fun pointRect(
            x: Int,
            y: Int,
        ): Rect = Rect(x, y, x + 1, y + 1)

        /** 滑动的注入区域：起止点的包围盒（P2）。 */
        private fun spanRect(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
        ): Rect = Rect(minOf(fromX, toX), minOf(fromY, toY), maxOf(fromX, toX) + 1, maxOf(fromY, toY) + 1)

        /** shell 注入点击：通道已连接时放行触摸盾＋标记自身动作窗口后注入 `input` 命令。 */
        private suspend fun shellTap(
            x: Int,
            y: Int,
            durationMs: Long,
        ): ShellAttempt =
            shellAction(pointRect(x, y)) { shellInput.tap(x, y, durationMs) }.also { attempt ->
                if (attempt is ShellAttempt.Done) {
                    logger.i(TAG, EVENT_CHANNEL, "op" to OP_TAP, "channel" to CHANNEL_SHELL, "x" to x, "y" to y)
                }
            }

        /** shell 注入滑动：同 shellTap。 */
        private suspend fun shellSwipe(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
            durationMs: Long,
        ): ShellAttempt =
            shellAction(
                spanRect(fromX, fromY, toX, toY),
            ) { shellInput.swipe(fromX, fromY, toX, toY, durationMs) }.also { attempt ->
                if (attempt is ShellAttempt.Done) {
                    logger.i(
                        TAG,
                        EVENT_CHANNEL,
                        "op" to OP_SWIPE,
                        "channel" to CHANNEL_SHELL,
                        "from" to "$fromX,$fromY",
                        "to" to "$toX,$toY",
                    )
                }
            }

        /**
         * shell 注入包裹（WP7.4）：通道未连接返回 [ShellAttempt.Skipped]（NOT_CONNECTED）；
         * 命令返回 false 或抛出非取消异常返回 Skipped（COMMAND_FAILED，异常记 warn）；
         * 注入同走输入管道，盾放行＋自身动作标记。
         */
        @Suppress("TooGenericExceptionCaught") // shell 通道实现可能抛任意运行时异常，统一转 COMMAND_FAILED（WP7.4）
        private suspend fun shellAction(
            target: Rect?,
            block: suspend () -> Boolean,
        ): ShellAttempt {
            if (shellInput.state.value != ShellState.Connected) return ShellAttempt.Skipped(ShellSkip.NOT_CONNECTED)
            val ok =
                try {
                    shielded(target) { selfAction { block() } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "shell_command_failed", error = e)
                    false
                }
            return if (ok) ShellAttempt.Done else ShellAttempt.Skipped(ShellSkip.COMMAND_FAILED)
        }

        /**
         * 全局动作。优先走 shell keyevent 注入（真实按键事件，应用无法屏蔽），
         * 失败回落无障碍 performGlobalAction（WP7.4：带没走 shell 的原因）。
         */
        override suspend fun globalAction(action: GlobalAction): InjectionResult {
            val keyCode = keyCodeOf(action)
            val attempt = if (keyCode != null) shellAction(null) { shellInput.keyEvent(keyCode) } else null
            if (attempt is ShellAttempt.Done) {
                logger.i(TAG, EVENT_CHANNEL, "op" to OP_GLOBAL_ACTION, "channel" to CHANNEL_SHELL, "keyCode" to keyCode)
                return InjectionResult.shell(ok = true)
            }
            // 回落无障碍 API
            logger.i(TAG, EVENT_CHANNEL, "op" to OP_GLOBAL_ACTION, "channel" to CHANNEL_A11Y)
            val ok = shielded(null) { selfAction { holder.current()?.performGlobalAction(globalId(action)) == true } }
            // 无对应 keyevent 的动作（通知面板等）首选就是无障碍，没有 shell 跳过原因。
            return if (attempt == null) InjectionResult.accessibility(ok, null) else injectionResultOf(attempt, ok)
        }

        /** shell 注入按键（input_text 的粘贴/回车等）：统一走盾放行＋自身动作标记。 */
        override suspend fun shellKeyEvent(code: Int): Boolean =
            shellAction(null) { shellInput.keyEvent(code) } is ShellAttempt.Done

        /** shell 注入组合键（如 Ctrl+A）。 */
        @Suppress("SpreadOperator") // 组合键只有 2 键，一次性小数组拷贝可忽略；vararg 端口只能展开传入
        override suspend fun shellKeyCombination(codes: List<Int>): Boolean =
            shellAction(null) { shellInput.keyCombination(*codes.toIntArray()) } is ShellAttempt.Done

        /** GlobalAction 映射到 KeyEvent KEYCODE_*。 */
        private fun keyCodeOf(action: GlobalAction): Int? =
            when (action) {
                GlobalAction.BACK -> KEYCODE_BACK
                GlobalAction.HOME -> KEYCODE_HOME
                GlobalAction.RECENTS -> KEYCODE_APP_SWITCH
                GlobalAction.NOTIFICATIONS -> null // 无对应 keyevent
                GlobalAction.QUICK_SETTINGS -> null // 无对应 keyevent
                GlobalAction.LOCK_SCREEN -> null // 无对应 keyevent
            }

        private suspend fun stroke(
            path: Path,
            durationMs: Long,
            op: String,
            target: Rect?,
        ): Boolean {
            val service = holder.current() ?: return false
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            // N186/N191：注入手势走输入管道，会被触摸盾截获——动作期间临时放行。
            logger.i(TAG, EVENT_CHANNEL, "op" to op, "channel" to CHANNEL_A11Y)
            return shielded(target) { selfAction { dispatchGesture(service, gesture) } }
        }

        /**
         * 注入动作放行包裹（N186/N191）：走输入管道的动作（dispatchGesture 注入手势、
         * performGlobalAction 注入按键）期间临时放行触摸盾（beginAction 等放行落地后才注入，
         * 动作完成才收尾保证覆盖全程）；performNodeAction 不走输入管道无需放行。
         */
        private suspend fun shielded(
            target: Rect?,
            block: suspend () -> Boolean,
        ): Boolean {
            touchShield.beginAction(target)
            return try {
                block()
            } finally {
                touchShield.endAction()
            }
        }

        private suspend fun dispatchGesture(
            service: AccessibilityService,
            gesture: GestureDescription,
        ): Boolean =
            suspendCancellableCoroutine { cont ->
                service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            cont.resume(false)
                        }
                    },
                    null,
                )
            }

        /** 自身动作窗口登记（WP7.3）：动作开始前先登记上限窗口，动作完成后按实际结束＋宽限补登记。 */
        private suspend fun selfAction(block: suspend () -> Boolean): Boolean {
            val cfg = configProvider.current()[DeviceConfig.TOUCH]
            val start = clock.nowMillis()
            activityMonitor.markSelfActionWindow(start, start + cfg.selfActionMaxMs)
            val ok = block()
            activityMonitor.markSelfActionWindow(start, clock.nowMillis() + cfg.selfActionGraceMs)
            return ok
        }

        private fun globalId(action: GlobalAction): Int =
            when (action) {
                GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
                GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                GlobalAction.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            }
    }
