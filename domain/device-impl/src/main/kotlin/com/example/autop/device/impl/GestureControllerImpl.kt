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
import com.example.autop.device.ResolvedNode
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import com.example.autop.device.UserActivityMonitor
import com.example.autop.device.impl.touch.TouchShieldController
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
        ): Boolean {
            if (shellTap(x, y, durationMs)) return true
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            return stroke(path, durationMs, OP_TAP)
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
        ): Boolean {
            if (shellSwipe(fromX, fromY, toX, toY, durationMs)) return true
            val path = buildSwipePath(fromX, fromY, toX, toY, jitterPx)
            return stroke(path, durationMs, OP_SWIPE)
        }

        /** 当前焦点输入框；没有返回 null（§5.3）。 */
        override suspend fun focusedInput(): ResolvedNode? {
            val service = holder.current() ?: return null
            val node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            val snapshot = NodeSnapshotReader.read(node, idx = 0, parent = null, childIndex = 0)
            return DirectResolvedNode(snapshot, node.windowId, node)
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

        /** shell 注入点击：通道已连接时放行触摸盾＋标记自身动作窗口后注入 `input` 命令。 */
        private suspend fun shellTap(
            x: Int,
            y: Int,
            durationMs: Long,
        ): Boolean =
            shellAction { shellInput.tap(x, y, durationMs) }.also { ok ->
                if (ok) logger.i(TAG, EVENT_CHANNEL, "op" to OP_TAP, "channel" to CHANNEL_SHELL, "x" to x, "y" to y)
            }

        /** shell 注入滑动：同 shellTap。 */
        private suspend fun shellSwipe(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
            durationMs: Long,
        ): Boolean =
            shellAction { shellInput.swipe(fromX, fromY, toX, toY, durationMs) }.also { ok ->
                if (ok) {
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

        /** shell 注入包裹：通道未连接直接回落（返回 false）；注入同走输入管道，盾放行＋自身动作标记。 */
        private suspend fun shellAction(block: suspend () -> Boolean): Boolean {
            if (shellInput.state.value != ShellState.Connected) return false
            return shielded { selfAction { block() } }
        }

        /**
         * 全局动作。优先走 shell keyevent 注入（真实按键事件，应用无法屏蔽），
         * 失败回落无障碍 performGlobalAction。
         */
        override suspend fun globalAction(action: GlobalAction): Boolean {
            // 优先 shell keyevent
            val keyCode = keyCodeOf(action)
            if (keyCode != null && shellKeyEvent(keyCode)) return true
            // 回落无障碍 API
            logger.i(TAG, EVENT_CHANNEL, "op" to OP_GLOBAL_ACTION, "channel" to CHANNEL_A11Y)
            return shielded { selfAction { holder.current()?.performGlobalAction(globalId(action)) == true } }
        }

        /** shell 注入按键事件。 */
        private suspend fun shellKeyEvent(keyCode: Int): Boolean =
            shellAction { shellInput.keyEvent(keyCode) }.also { ok ->
                if (ok) {
                    logger.i(
                        TAG,
                        EVENT_CHANNEL,
                        "op" to OP_GLOBAL_ACTION,
                        "channel" to CHANNEL_SHELL,
                        "keyCode" to keyCode,
                    )
                }
            }

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
        ): Boolean {
            val service = holder.current() ?: return false
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            // N186/N191：注入手势走输入管道，会被触摸盾截获——动作期间临时放行。
            logger.i(TAG, EVENT_CHANNEL, "op" to op, "channel" to CHANNEL_A11Y)
            return shielded { selfAction { dispatchGesture(service, gesture) } }
        }

        /**
         * 注入动作放行包裹（N186/N191）：走输入管道的动作（dispatchGesture 注入手势、
         * performGlobalAction 注入按键）期间临时放行触摸盾（beginAction 等放行落地后才注入，
         * 动作完成才收尾保证覆盖全程）；performNodeAction 不走输入管道无需放行。
         */
        private suspend fun shielded(block: suspend () -> Boolean): Boolean {
            touchShield.beginAction()
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

        private suspend fun selfAction(block: suspend () -> Boolean): Boolean {
            val start = clock.nowMillis()
            val ok = block()
            val grace = configProvider.current()[DeviceConfig.TOUCH].selfActionGraceMs
            activityMonitor.markSelfActionWindow(start, clock.nowMillis() + grace)
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
