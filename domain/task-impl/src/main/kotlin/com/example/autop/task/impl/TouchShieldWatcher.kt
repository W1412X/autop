/**
 * 触摸盾驱动（docs/tech/07 §4.3，N186）：Run 执行期按 [RunState] 驱动
 * [com.example.autop.device.TouchShield] 屏蔽用户屏幕触摸——
 * 执行态（STARTING / SAMPLING / EXECUTING_TOOLS / COMPACTING）屏蔽，
 * PAUSED / WAITING_USER / FINISHED 解除（ask_user 与「我已手动完成」需用户操作手机）。
 *
 * N186 取代原 touch-pause（用户拍板）：执行期用户点击对屏幕不生效、不再暂停任务；
 * 停止任务（悬浮球/通知）后自然可触摸。悬浮球与浮层窗口是独立窗口，在盾之上照常交互；
 * 工具注入手势由 GestureControllerImpl 动作期间放行（device-impl 内部协作）。
 */
package com.example.autop.task.impl

import com.example.autop.agent.RunHandle
import com.example.autop.agent.RunState
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.device.DisplayTarget
import com.example.autop.device.RunDisplayController
import com.example.autop.device.TouchShield
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Run 执行期的触摸屏蔽装配（watch 的取消由执行器负责）。 */
@Singleton
public class TouchShieldWatcher
    @Inject
    constructor(
        private val shield: TouchShield,
        private val displayController: RunDisplayController,
    ) {
        /** 解析本次 Run 的显示目标；实际资源释放由 [releaseDisplay] 收口。 */
        internal suspend fun openDisplay(
            owner: RunId,
            backgroundScreen: Boolean,
        ): Outcome<DisplayTarget> = displayController.open(owner, backgroundScreen)

        /** 释放本次 Run 的显示资源。 */
        internal suspend fun releaseDisplay(owner: RunId): Unit = displayController.release(owner)

        /**
         * 新 Run 开始（agent 启动**前**同步调用，N217）：真实屏清除上一 Run 的强制解除抑制、
         * 恢复状态驱动。必须同步落地——放在 [watch] 协程里会异步延迟，可能晚于用户
         * 「停止」的 `forceRelease()` 落地，把已强制解除的盾又拉起来。
         * 后台屏不驱动真实屏触摸盾。
         */
        public fun beginRun(
            owner: RunId,
            target: DisplayTarget,
        ) {
            RunDisplayTargetRegistry.bind(owner, target)
            if (target is DisplayTarget.Main) shield.reset()
        }

        /** 按 Run 状态与屏幕占用驱动屏蔽（ADR-0035：只有本 Run 持有屏幕时才屏蔽）；协程由 RunRunner 在 Run 结束时取消（finally 兜底解除）。 */
        public suspend fun watch(
            handle: RunHandle,
            owner: String,
            holder: StateFlow<String?>,
        ) {
            try {
                handle.state
                    .combine(holder) { state, current -> shouldShield(state) && current == owner }
                    .collect { shield.setActive(it) }
            } finally {
                shield.setActive(false)
            }
        }
    }

/** Run 取消入口按显示目标强制解除真实屏触摸盾；未知目标不释放其他 Run 的共享盾。 */
internal object RunDisplayTargetRegistry {
    private val runTargets = ConcurrentHashMap<RunId, RunTarget>()

    fun bind(
        owner: RunId,
        target: DisplayTarget,
    ) {
        runTargets[owner] = if (target is DisplayTarget.Background) RunTarget.BACKGROUND else RunTarget.MAIN
    }

    /** 显示打开前先登记未知态，覆盖 open 挂起时的取消窗口。 */
    fun bindOpening(owner: RunId) {
        runTargets[owner] = RunTarget.OPENING
    }

    fun clear(owner: RunId) {
        runTargets.remove(owner)
    }

    fun forceRelease(
        owner: RunId,
        shield: TouchShield,
    ) {
        if (runTargets[owner] == RunTarget.MAIN) shield.forceRelease()
    }

    private enum class RunTarget {
        OPENING,
        MAIN,
        BACKGROUND,
    }
}

/** 是否应在该 Run 状态下屏蔽用户屏幕触摸（docs/tech/07 §4.3）：执行态集合同源
 * [com.example.autop.agent.RunState.EXECUTION_STATES]（暂停门吞并迁移用同一集合）。 */
internal fun shouldShield(state: RunState): Boolean = state in RunState.EXECUTION_STATES
