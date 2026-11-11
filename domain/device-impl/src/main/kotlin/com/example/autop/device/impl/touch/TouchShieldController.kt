/**
 * 触摸盾控制（docs/tech/05 §8，N186/N191）：全屏拦截窗口的常驻与开关。
 *
 * - **z 序**：`start()` 在其他浮层注册前调用，窗口按添加顺序垫底（同 type 后加者在上），
 *   悬浮球 / 菜单 / 面板 / 进度窗永远在盾之上；通道重建按登记序重附，顺序不变。
 * - **开关**：不 show/hide，而是 `spec.touchable` 切换 `FLAG_NOT_TOUCHABLE`
 *   （`OverlayWindowRegistry.update` 全量重建 LayoutParams），窗口常驻保 z 序。
 * - **注入让路**（N216 → N222 收窄）：`GestureControllerImpl` 的注入手势（`dispatchGesture`）与
 *   全局动作（`performGlobalAction` 注入 KEYCODE_BACK）走输入管道，会被 touchable 的盾截获，
 *   动作期间 `beginAction(footprint)/endAction(footprint)` 临时放行（可重入）；
 *   `performNodeAction` 不走输入管道，无需放行。让路不再一刀切封锁全部窗口：
 *   按本次注入的占位（[InjectionFootprint]）只让冲突窗口让路——手势看落点是否在其矩形内
 *   （触摸的窗口归属在 DOWN 时确定），按键只看可聚焦窗口（按键只送焦点窗口）。
 *   N191：`beginAction` 等放行落地后才返回（放行与注入之间无竞态窗口）；状态落地在
 *   主线程执行时重新求值，过期排队任务不会用旧值覆盖新状态。
 *   N214：只提交 LayoutParams 不算放行生效——`FLAG_NOT_TOUCHABLE` 要等 WMS 重算
 *   touchable region 并同步 InputDispatcher 才不拦触摸，`beginAction` 额外等布局落地
 *   （`OverlayWindowManager.awaitRelayoutApplied`）后才允许注入。
 *   N222：落地后自检仍可能吞掉本次注入的自家窗口（新窗口恰在注入期附着/矩形变化），
 *   自检不过则拒绝本次注入并留痕（`inject_blocked_by_own_overlay`），不再盲点自家 UI。
 * - **强制解除**（N191，安全关键）：用户中止任务 `forceRelease()` 立即解除并抑制后续
 *   激活（Run 卡死也保证用户能操作手机），新 Run 开始 `reset()` 恢复。
 * - **安全阀**：`ui.overlay.enabled` 关闭（悬浮球不显示，无停止入口）时不屏蔽。
 */
package com.example.autop.device.impl.touch

import android.view.WindowManager
import com.example.autop.core.common.Logger
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.config.UiConfig
import com.example.autop.device.InjectionFootprint
import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlaySpec
import com.example.autop.device.TouchShield
import com.example.autop.device.impl.overlay.OverlayWindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** [TouchShield] 实现。 */
@Singleton
public class TouchShieldController
    @Inject
    constructor(
        private val overlay: OverlayWindowManager,
        private val configProvider: ConfigProvider,
        private val logger: Logger,
    ) : TouchShield {
        /** Run 执行态屏蔽开关（task-impl 驱动）。 */
        @Volatile
        private var active: Boolean = false

        /** 进行中的注入动作数（可重入；0 = 无动作）。 */
        private val actionDepth: AtomicInteger = AtomicInteger(0)

        /** 注入进行中（N222）：盾放行 + 浮层让路期间为 true，UI 控制入口据此防误触。 */
        private val _injecting = MutableStateFlow(false)

        /** 注入进行中（N222）：控制条/面板按钮在这期间忽略点击（见 [TouchShield.injecting]）。 */
        override val injecting: StateFlow<Boolean> = _injecting.asStateFlow()

        /** 强制解除（用户中止）抑制标志（N191）。 */
        @Volatile
        private var forceReleased: Boolean = false

        /** 常驻注册并显示（app 启动时、其他浮层注册前调用一次；幂等）。 */
        public fun start() {
            overlay.register(SHIELD_ID) { context, _ -> TouchShieldView(context) }
            overlay.show(SHIELD_ID, shieldSpec(touchable = shielding()))
        }

        override fun setActive(active: Boolean) {
            this.active = active
            logger.i(TAG, "shield_active", "active" to active, "shielding" to shielding())
            apply()
        }

        /** 强制解除屏蔽并抑制后续激活（N191，安全关键：中止任务立即可操作手机）。 */
        override fun forceRelease() {
            forceReleased = true
            logger.i(TAG, "shield_force_release")
            apply()
        }

        /** 清除强制解除抑制（新 Run 开始时）。 */
        override fun reset() {
            forceReleased = false
            apply()
        }

        /**
         * 注入动作开始：放行触摸、按占位让冲突浮层让路，并等落地（N191：落地前注入会被盾截获）。
         * N214：参数提交 ≠ 盾已不拦触摸——`FLAG_NOT_TOUCHABLE` 要等 WMS 重算
         * touchable region 并同步 InputDispatcher 才生效，等落地再注入，否则注入仍被盾吞。
         * N222：只让与 [footprint] 冲突的可触摸窗口让路（见 [InjectionFootprint]），
         * 不冲突的窗口保持可见可触摸；重排未落地或落地后自检仍冲突则拒绝本次注入
         * （返回 false），由调用方报注入失败，而不是盲点自家 UI。
         * 失败/取消时回退深度并解除本次占位，防永久放行。
         */
        public suspend fun beginAction(footprint: InjectionFootprint): Boolean {
            actionDepth.incrementAndGet()
            _injecting.value = true
            var committed = false
            val timeoutMs = relayoutTimeoutMs()
            try {
                overlay.updateNow(SHIELD_ID, ::shieldSpecOf)
                val landed = overlay.occlude(footprint, timeoutMs)
                val stillTouchable = overlay.conflictingTouchableWindows()
                val occluded = overlay.occludingRects()
                logger.i(
                    TAG,
                    "shield_release",
                    "depth" to actionDepth.get(),
                    "shielding" to shielding(),
                    "landed" to landed,
                    "footprint" to footprintLabel(footprint),
                    "occluded" to occluded.joinToString(","),
                    "stillTouchable" to stillTouchable.joinToString(","),
                )
                if (!landed) {
                    logger.e(
                        TAG,
                        "inject_blocked_relayout_timeout",
                        "footprint" to footprintLabel(footprint),
                        "timeoutMs" to timeoutMs,
                    )
                    return false
                }
                if (stillTouchable.isNotEmpty()) {
                    logger.e(
                        TAG,
                        "inject_blocked_by_own_overlay",
                        "footprint" to footprintLabel(footprint),
                        "ids" to stillTouchable.joinToString(","),
                    )
                    return false
                }
                committed = true
                return true
            } finally {
                if (!committed) {
                    // 拒绝注入 / 取消异常：回退深度与本次占位，调用方不再 endAction。
                    val depth = actionDepth.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
                    _injecting.value = depth > 0
                    overlay.restoreOcclusion(footprint)
                    apply()
                }
            }
        }

        /**
         * 注入动作结束：解除本次占位并恢复拦截（落地允许异步——漏拦截仅毫秒级，且落地时取实时值）。
         * N222：只有最后一个动作结束（深度回 0）才恢复盾的拦截与 `injecting=false`。
         */
        public fun endAction(footprint: InjectionFootprint) {
            val depth = actionDepth.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            if (depth == 0) _injecting.value = false
            logger.i(
                TAG,
                "shield_restore",
                "depth" to depth,
                "shielding" to shielding(),
                "footprint" to footprintLabel(footprint),
            )
            overlay.restoreOcclusion(footprint)
            apply()
        }

        /** 当前是否应拦截用户触摸（纯逻辑，供单测）。 */
        internal fun shielding(): Boolean =
            shieldTouchable(
                active = active,
                actionDepth = actionDepth.get(),
                overlayEnabled = overlayEnabled(),
                forceReleased = forceReleased,
                shieldEnabled = shieldEnabled(),
            )

        private fun overlayEnabled(): Boolean = configProvider.current()[UiConfig.OVERLAY].enabled

        /** 触摸盾总开关（关闭后运行中不屏蔽用户触摸，N215）。 */
        private fun shieldEnabled(): Boolean = configProvider.current()[DeviceConfig.TOUCH].shieldEnabled

        /** 放行后等重排落地的上限（N214）。 */
        private fun relayoutTimeoutMs(): Long = configProvider.current()[DeviceConfig.TOUCH].shieldRelayoutTimeoutMs

        /** 落地时（主线程执行时）重新求值：过期排队任务不会用旧值覆盖新状态（N191）。 */
        private fun shieldSpecOf(): OverlaySpec = shieldSpec(touchable = shielding())

        private fun apply() {
            overlay.updateLazy(SHIELD_ID, ::shieldSpecOf)
        }

        private companion object {
            const val TAG = "TouchShield"
        }
    }

/** 盾是否拦截触摸：执行态 ∧ 盾开关开 ∧ 无强制解除 ∧ 无进行中注入动作 ∧ 悬浮球开启（安全阀）。 */
internal fun shieldTouchable(
    active: Boolean,
    actionDepth: Int,
    overlayEnabled: Boolean,
    forceReleased: Boolean,
    shieldEnabled: Boolean,
): Boolean = active && shieldEnabled && !forceReleased && actionDepth == 0 && overlayEnabled

/**
 * 盾窗口 spec：铺满整个屏幕（MATCH_PARENT，<= 0 的宽高在触摸探针矩形判定里视为不存在，
 * 用户触摸照常计入 lastUserTouchAt），不可聚焦。[OverlaySpec.fullScreen] 让盾延伸到状态栏与导航栏区域，
 * 否则默认窗口被摆在系统栏之内，执行期用户仍能下拉通知栏。
 */
private fun shieldSpec(touchable: Boolean): OverlaySpec =
    OverlaySpec(
        x = 0,
        y = 0,
        width = WindowManager.LayoutParams.MATCH_PARENT,
        height = WindowManager.LayoutParams.MATCH_PARENT,
        touchable = touchable,
        focusable = false,
        gravity = OverlayGravity.TOP_LEFT,
        fullScreen = true,
    )

/** 盾窗口 id（`OverlayWindowManager.register/show/update` 的键）。 */
internal const val SHIELD_ID: String = "overlay.touch_shield"

/** 注入占位的日志文案（N222）。 */
private fun footprintLabel(footprint: InjectionFootprint): String =
    when (footprint) {
        InjectionFootprint.All -> "all"
        InjectionFootprint.Focus -> "focus"
        is InjectionFootprint.TouchPoint -> "point(${footprint.x},${footprint.y}±${footprint.marginPx})"
    }
