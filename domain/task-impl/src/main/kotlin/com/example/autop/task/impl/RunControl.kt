/**
 * 等待中 Run 的控制面（docs/tech/07 §4.3/§4.4）：锁屏提醒的执行/稍后/跳过与
 * 恢复执行（策略拦截后“我已手动完成，继续”）。
 */
package com.example.autop.task.impl

import com.example.autop.core.common.RunId

/** 等待中 Run 的控制。 */
public interface RunControl {
    /** 立即重新入队并调度（解锁后执行）。 */
    public fun requeueNow(runId: RunId)

    /** 稍后 [minutes] 分钟再提醒（Run 保持 WAITING_USER）。 */
    public fun snooze(
        runId: RunId,
        minutes: Int,
    )

    /** 跳过（Run → CANCELLED）。 */
    public fun cancelWaiting(runId: RunId)

    /** 策略拦截后恢复执行（“我已手动完成，继续”）。 */
    public fun resumeBlocked(
        runId: RunId,
        note: String?,
    )
}
