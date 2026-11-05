/**
 * [RunControl] 实现（docs/tech/07 §4.3/§4.4）：锁屏提醒的执行/稍后/跳过与
 * 策略拦截后的恢复执行。
 */
package com.example.autop.task.impl

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.autop.core.common.Logger
import com.example.autop.core.common.RunId
import com.example.autop.task.impl.reminder.LockReminder
import com.example.autop.task.impl.reminder.SnoozeReceiver
import dagger.hilt.android.qualifiers.ApplicationContext

/** 等待中 Run 的控制实现。 */
public class RunControlImpl(
    @ApplicationContext private val context: Context,
    private val scheduler: RunScheduler,
    private val observer: RunObserver,
    private val lockReminder: LockReminder,
    private val logger: Logger,
) : RunControl {
    override fun requeueNow(runId: RunId) {
        scheduler.requeue(runId)
    }

    override fun snooze(
        runId: RunId,
        minutes: Int,
    ) {
        lockReminder.dismiss(runId)
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, SnoozeReceiver::class.java).putExtra(SnoozeReceiver.EXTRA_RUN_ID, runId.value)
        val pending =
            PendingIntent.getBroadcast(
                context,
                (runId.value + minutes).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        manager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + minutes * MINUTE_MS,
            pending,
        )
    }

    override fun cancelWaiting(runId: RunId) {
        scheduler.cancelQueued(runId)
    }

    override fun resumeBlocked(
        runId: RunId,
        note: String?,
    ) {
        val handle = observer.handleOf(runId)
        if (handle == null) {
            logger.w(TAG, "resume_no_active_run", "runId" to runId.value)
            return
        }
        handle.resume(note)
    }

    private companion object {
        const val TAG = "RunControl"
        const val MINUTE_MS = 60_000L
    }
}
