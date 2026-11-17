package com.example.autop.task.impl.notify

import com.example.autop.task.RunStatus

/** 通知大图标的静态帧（P1-15）：通知不能动画，只保留可辨识的代表帧。 */
internal enum class NotifyFace {
    IDLE,
    WAITING,
    SUCCESS,
    FAILURE,
    BLOCKED,
    LOST,
}

/** Run 结局 → 通知大图标表情；取消/中断不做强负反馈，与列表口径一致。 */
internal fun notifyFaceOf(status: RunStatus?): NotifyFace =
    when (status) {
        RunStatus.SUCCEEDED -> NotifyFace.SUCCESS
        RunStatus.FAILED, RunStatus.BUDGET_EXHAUSTED, RunStatus.BREAKER_STOPPED, RunStatus.EXPIRED ->
            NotifyFace.FAILURE
        else -> NotifyFace.IDLE
    }
